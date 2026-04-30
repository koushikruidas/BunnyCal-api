package com.daedalussystems.easySchedule.availability.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.booking.domain.Booking;
import com.daedalussystems.easySchedule.availability.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class BookingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private SlotCacheService slotCacheService;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bookingService = new BookingService(userRepository, bookingRepository, slotCacheService);
    }

    @Test
    void createBooking_success_locksUserChecksOverlapSavesAndInvalidatesCache() {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(User.builder().id(userId).email("u@e.com").name("U").timezone("UTC").build()));
        when(bookingRepository.findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan(userId, end, start)).thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking b = invocation.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        Booking saved = bookingService.createBooking(userId, eventTypeId, start, end);

        assertNotNull(saved.getId());
        verify(userRepository, times(1)).findByIdForUpdate(userId);
        verify(bookingRepository, times(1)).findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan(userId, end, start);
        verify(bookingRepository, times(1)).save(any(Booking.class));
        verify(slotCacheService, times(1)).invalidateUser(userId);
    }

    @Test
    void createBooking_overlap_throwsValidationError() {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(User.builder().id(userId).email("u@e.com").name("U").timezone("UTC").build()));
        when(bookingRepository.findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan(userId, end, start))
                .thenReturn(List.of(Booking.builder().id(UUID.randomUUID()).userId(userId).eventTypeId(eventTypeId).startTime(start).endTime(end).build()));

        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.createBooking(userId, eventTypeId, start, end));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        verify(bookingRepository, times(0)).save(any(Booking.class));
    }

    @Test
    void chaos_concurrentBooking_onlyOneSucceedsAfterAuthoritativeRecheck() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(User.builder().id(userId).email("u@e.com").name("U").timezone("UTC").build()));

        ReentrantLock simulatedDbUserRowLock = new ReentrantLock(true);
        List<Booking> persisted = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            simulatedDbUserRowLock.lock();
            return Optional.of(User.builder().id(userId).email("u@e.com").name("U").timezone("UTC").build());
        }).when(userRepository).findByIdForUpdate(userId);

        doAnswer(invocation -> {
            List<Booking> overlaps = new ArrayList<>();
            for (Booking booking : persisted) {
                boolean overlap = booking.getStartTime().isBefore(end) && booking.getEndTime().isAfter(start);
                if (overlap) {
                    overlaps.add(booking);
                }
            }
            if (!overlaps.isEmpty() && simulatedDbUserRowLock.isHeldByCurrentThread()) {
                simulatedDbUserRowLock.unlock();
            }
            return overlaps;
        }).when(bookingRepository).findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan(eq(userId), eq(end), eq(start));

        doAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setId(UUID.randomUUID());
            persisted.add(booking);
            if (simulatedDbUserRowLock.isHeldByCurrentThread()) {
                simulatedDbUserRowLock.unlock();
            }
            return booking;
        }).when(bookingRepository).save(any(Booking.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<Boolean> f1 = executor.submit(() -> {
            startGate.await(2, TimeUnit.SECONDS);
            try {
                bookingService.createBooking(userId, eventTypeId, start, end);
                return true;
            } catch (CustomException ex) {
                return false;
            }
        });

        Future<Boolean> f2 = executor.submit(() -> {
            startGate.await(2, TimeUnit.SECONDS);
            try {
                bookingService.createBooking(userId, eventTypeId, start, end);
                return true;
            } catch (CustomException ex) {
                return false;
            }
        });

        startGate.countDown();

        boolean r1 = f1.get(3, TimeUnit.SECONDS);
        boolean r2 = f2.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        int successCount = (r1 ? 1 : 0) + (r2 ? 1 : 0);
        assertEquals(1, successCount);
        assertEquals(1, persisted.size());
        verify(slotCacheService, times(1)).invalidateUser(userId);
    }
}
