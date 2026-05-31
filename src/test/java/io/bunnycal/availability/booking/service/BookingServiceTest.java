package io.bunnycal.availability.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.booking.service.BookingService;
import io.bunnycal.booking.service.BookingConferencingCapabilityGuard;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.booking.service.CancellationSource;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private OutboxPublisher outboxPublisher;

    @Mock
    private TimeSource timeSource;
    @Mock
    private BookingConferencingCapabilityGuard conferencingCapabilityGuard;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(timeSource.now()).thenReturn(java.time.Instant.now());
        bookingService = new BookingService(
                userRepository, bookingRepository, outboxPublisher,
                timeSource, new SimpleMeterRegistry());
    }

    @Test
    void createBooking_success_locksUser_saves_and_publishesOutbox() {
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

        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> {
            Booking b = invocation.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        Booking saved = bookingService.createBooking(userId, eventTypeId, start, end);

        assertNotNull(saved.getId());

        verify(userRepository, times(1)).findByIdForUpdate(userId);
        verify(bookingRepository, times(1)).saveAndFlush(any(Booking.class));
        verify(outboxPublisher, times(1)).publish(eq("Booking"), eq(saved.getId()), eq(userId), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void confirmHeldBooking_rejectsUnsupportedConsumerMsaTeamsBeforeConfirmation() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-10T10:00:00Z");
        Instant end = Instant.parse("2026-05-10T10:30:00Z");

        BookingService guardedService = new BookingService(
                userRepository, bookingRepository, outboxPublisher, timeSource,
                new SimpleMeterRegistry(), null, null, conferencingCapabilityGuard);

        when(bookingRepository.findStateById(bookingId))
                .thenReturn(Optional.of(stateRow(bookingId, hostId, "PENDING", 2L)));
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .eventTypeId(eventTypeId)
                        .startTime(start)
                        .endTime(end)
                        .build()));
        org.mockito.Mockito.doThrow(new CustomException(
                ErrorCode.VALIDATION_ERROR,
                "Microsoft Teams conferencing requires a Microsoft 365 work/school account. "
                        + "Personal Outlook.com accounts are not supported for native Teams meeting provisioning."))
                .when(conferencingCapabilityGuard)
                .assertBookingConfirmationSupported(bookingId, hostId, eventTypeId);

        CustomException ex = assertThrows(CustomException.class, () -> guardedService.confirmHeldBooking(bookingId));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        verify(bookingRepository, never()).updateStatusAndCalendarSequence(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(outboxPublisher, never()).publish(eq("Booking"), eq(bookingId), eq(hostId), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void updateBooking_success_persistsAndPublishesOutbox() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-01T10:00:00Z");
        Instant end = Instant.parse("2026-05-01T11:00:00Z");

        when(bookingRepository.updateWindowAndCalendarSequence(bookingId, hostId, start, end, 3L)).thenReturn(1);

        bookingService.updateBooking(bookingId, hostId, start, end, 3L);

        verify(bookingRepository, times(1)).updateWindowAndCalendarSequence(bookingId, hostId, start, end, 3L);
        verify(outboxPublisher, times(1)).publish(eq("Booking"), eq(bookingId), eq(hostId), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void cancelBooking_success_publishesOutbox() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        when(bookingRepository.updateStatusAndCalendarSequenceAndIntentEpoch(bookingId, "PENDING", "CANCELLED", 4L)).thenReturn(1);

        bookingService.cancelBooking(bookingId, hostId, 4L);

        verify(bookingRepository, times(1)).updateStatusAndCalendarSequenceAndIntentEpoch(bookingId, "PENDING", "CANCELLED", 4L);
        verify(outboxPublisher, times(1)).publish(eq("Booking"), eq(bookingId), eq(hostId), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void cancelBooking_includesCancellationMetadataInOutboxEnvelope() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        when(bookingRepository.updateStatusAndCalendarSequenceAndIntentEpoch(bookingId, "PENDING", "CANCELLED", 7L)).thenReturn(1);

        bookingService.cancelBooking(bookingId, hostId, 7L, CancellationSource.HOST, "USER_REQUEST");

        org.mockito.ArgumentCaptor<OutboxPayloadEnvelope> captor =
                org.mockito.ArgumentCaptor.forClass(OutboxPayloadEnvelope.class);
        verify(outboxPublisher).publish(eq("Booking"), eq(bookingId), eq(hostId), captor.capture());
        OutboxPayloadEnvelope envelope = captor.getValue();
        Map<String, Object> metadata = envelope.metadata();
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        assertEquals("HOST", metadata.get("source"));
        assertEquals("USER_REQUEST", metadata.get("reasonCode"));
        assertEquals(hostId.toString(), metadata.get("cancelledBy"));
    }

    @Test
    void cancelBookingAsHost_alreadyCancelled_isNoopAndDoesNotEmitOutbox() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        when(bookingRepository.findStateById(bookingId))
                .thenReturn(Optional.of(stateRow(bookingId, hostId, "CANCELLED", 3L)));
        when(bookingRepository.findById(any()))
                .thenReturn(Optional.of(bookingEntity(bookingId, hostId)));

        bookingService.cancelBookingAsHost(bookingId, hostId, null);

        verify(outboxPublisher, never()).publish(eq("Booking"), eq(bookingId), any(UUID.class), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void cancelBookingAsHost_concurrentRace_emitsSingleOutboxEvent() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        when(bookingRepository.findStateById(bookingId)).thenAnswer(inv -> {
            if (cancelled.get()) {
                return Optional.of(stateRow(bookingId, hostId, "CANCELLED", 5L));
            }
            return Optional.of(stateRow(bookingId, hostId, "CONFIRMED", 4L));
        });
        when(bookingRepository.updateStatusAndCalendarSequenceAndIntentEpoch(bookingId, "PENDING", "CANCELLED", 4L)).thenReturn(0);
        when(bookingRepository.updateStatusAndCalendarSequenceAndIntentEpoch(bookingId, "CONFIRMED", "CANCELLED", 4L))
                .thenAnswer(inv -> cancelled.compareAndSet(false, true) ? 1 : 0);
        when(bookingRepository.findById(any()))
                .thenReturn(Optional.of(bookingEntity(bookingId, hostId)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        Callable<Void> task = () -> {
            startGate.await();
            bookingService.cancelBookingAsHost(bookingId, hostId, null);
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);
        startGate.countDown();
        f1.get();
        f2.get();
        executor.shutdownNow();

        verify(outboxPublisher, times(1)).publish(eq("Booking"), eq(bookingId), eq(hostId), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void bookingService_onlyUsesAllowedDependencyNamespaces() {
        Set<String> allowedPrefixes = Set.of(
                "io.bunnycal.booking.",
                "io.bunnycal.auth.",
                "io.bunnycal.sync.",
                "io.bunnycal.common.",
                "io.micrometer.",
                "org.slf4j.",
                "java."
        );
        for (Field field : BookingService.class.getDeclaredFields()) {
            if (field.isSynthetic() || field.getType().isPrimitive()) {
                continue;
            }
            String typeName = field.getType().getName();
            boolean allowed = allowedPrefixes.stream().anyMatch(typeName::startsWith);
            org.junit.jupiter.api.Assertions.assertTrue(
                    allowed,
                    "BookingService dependency outside allowlist: " + typeName);
        }
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

        when(bookingRepository.saveAndFlush(any()))
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
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("constraint violation", psqlCause));

        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.createBooking(hostId, eventTypeId, start, end));

        assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode());
        verify(bookingRepository, times(1)).saveAndFlush(any(Booking.class));
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

        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> {
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

    }

    private static Booking bookingEntity(UUID bookingId, UUID hostId) {
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setHostId(hostId);
        booking.setEventTypeId(UUID.randomUUID());
        booking.setStartTime(Instant.parse("2026-05-01T10:00:00Z"));
        booking.setEndTime(Instant.parse("2026-05-01T10:30:00Z"));
        booking.setCreatedAt(Instant.parse("2026-05-01T09:00:00Z"));
        return booking;
    }

    private static BookingRepository.BookingStateRow stateRow(UUID id, UUID hostId, String status, long version) {
        return new BookingRepository.BookingStateRow() {
            @Override public UUID getId() { return id; }
            @Override public UUID getHostId() { return hostId; }
            @Override public String getStatus() { return status; }
            @Override public Long getVersion() { return version; }
            @Override public Instant getExpiresAt() { return null; }
                    public Long getTerminalIntentEpoch() { return 0L; }
        };
    }
}
