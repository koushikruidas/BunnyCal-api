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
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

class BookingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private SlotCacheService slotCacheService;

    @Mock
    private OutboxPublisher outboxPublisher;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bookingService = new BookingService(userRepository, bookingRepository, slotCacheService, outboxPublisher);
    }

    @Test
    void createBooking_success_locksUser_saves_and_invalidatesCache() {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(userId))
                .thenReturn(Optional.of(User.builder()
                        .id(userId)
                        .email("u@e.com")
                        .name("U")
                        .timezone("UTC")
                        .build()));

        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking b = invocation.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        Booking saved = bookingService.createBooking(userId, eventTypeId, start, end);

        assertNotNull(saved.getId());

        verify(userRepository, times(1)).findByIdForUpdate(userId);
        verify(bookingRepository, times(1)).save(any(Booking.class));
        verify(slotCacheService, times(1)).invalidateUser(userId);
    }

    @Test
    void createBooking_overlap_throwsValidationError() {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(userId))
                .thenReturn(Optional.of(User.builder()
                        .id(userId).email("u@e.com").name("U").timezone("UTC").build()));

        when(bookingRepository.countOverlappingPending(any(), any(), any()))
                .thenReturn(0L);

        when(bookingRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "overlap",
                        new SQLException("bookings_no_overlap violation", "23P01")
                ));

        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.createBooking(userId, eventTypeId, start, end));

        assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode());
    }

    // Proves BookingService.isOverlapExclusionViolation walks the cause chain,
    // finds SQLState 23P01 + constraint name, and maps to SLOT_ALREADY_BOOKED.
    // The pre-check returns empty (no overlap seen), so save() is reached —
    // simulating a racer that slipped past the application-level pre-check and
    // was stopped by the EXCLUDE constraint.
    @Test
    void createBooking_dbExclusionConstraintFires_mapsToSlotAlreadyBooked() {
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end   = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(hostId))
                .thenReturn(Optional.of(User.builder().id(hostId).email("h@e.com").name("H").timezone("UTC").build()));
        when(bookingRepository.findActiveOverlappingBookings(hostId, end, start))
                .thenReturn(List.of()); // pre-check passes; constraint fires on save

        // Wrap a 23P01 SQLException (PSQLException subclass in production) so
        // the service's cause-chain walk can find it. Using plain SQLException
        // here because PSQLException is only on the runtime classpath.
        SQLException psqlCause = new SQLException(
                "ERROR: conflicting key value violates exclusion constraint \"bookings_no_overlap\"",
                "23P01");
        when(bookingRepository.save(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("constraint violation", psqlCause));

        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.createBooking(hostId, eventTypeId, start, end));

        assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode());
        verify(bookingRepository, times(1)).save(any(Booking.class));
    }

    @Test
    void chaos_concurrentBooking_onlyOneSucceeds_dueToDbConstraint() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-04-30T10:00:00Z");
        Instant end = Instant.parse("2026-04-30T10:30:00Z");

        when(userRepository.findByIdForUpdate(userId))
                .thenReturn(Optional.of(User.builder()
                        .id(userId)
                        .email("u@e.com")
                        .name("U")
                        .timezone("UTC")
                        .build()));

        AtomicBoolean first = new AtomicBoolean(true);
        List<Booking> persisted = new CopyOnWriteArrayList<>();

        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                Booking b = invocation.getArgument(0);
                b.setId(UUID.randomUUID());
                persisted.add(b);
                return b;
            } else {
                throw new DataIntegrityViolationException(
                        "constraint violation",
                        new SQLException("ERROR", "23P01")
                );
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<Boolean> task = () -> {
            startGate.await();
            try {
                bookingService.createBooking(userId, eventTypeId, start, end);
                return true;
            } catch (CustomException ex) {
                return false;
            }
        };

        Future<Boolean> f1 = executor.submit(task);
        Future<Boolean> f2 = executor.submit(task);

        startGate.countDown();

        boolean r1 = f1.get();
        boolean r2 = f2.get();

        executor.shutdownNow();

        int successCount = (r1 ? 1 : 0) + (r2 ? 1 : 0);

        assertEquals(1, successCount);
        assertEquals(1, persisted.size());

        verify(slotCacheService, times(1)).invalidateUser(userId);
    }
}
