package io.bunnycal.booking.outbox;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.notification.BookingNotificationService;
import io.bunnycal.booking.ownership.BookingOwnershipService;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class LoggingOutboxEventDispatcherTest {

    @Mock
    private CalendarSyncJobRepository calendarSyncJobRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private EventTypeRepository eventTypeRepository;
    @Mock
    private EventSessionRepository eventSessionRepository;
    @Mock
    private CalendarConnectionRepository calendarConnectionRepository;
    @Mock
    private BookingNotificationService bookingNotificationService;
    @Mock
    private io.bunnycal.session.notification.SessionNotificationService sessionNotificationService;
    @Mock
    private io.bunnycal.session.sync.SessionSyncWorker sessionSyncWorker;
    @Mock
    private BookingOwnershipService bookingOwnershipService;
    @Mock
    private SyncInvariantMonitor invariantMonitor;

    private LoggingOutboxEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new LoggingOutboxEventDispatcher(
                calendarSyncJobRepository,
                bookingRepository,
                eventTypeRepository,
                eventSessionRepository,
                calendarConnectionRepository,
                bookingOwnershipService,
                bookingNotificationService,
                sessionNotificationService,
                sessionSyncWorker,
                txManager(),
                invariantMonitor,
                new SimpleMeterRegistry());
    }

    @Test
    void dispatch_bookingConfirmed_enqueuesSyncJob() {
        UUID bookingId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("BOOKING"),
                eq(bookingId),
                eq(null),
                eq("CREATE"),
                eq(null),
                eq(null), eq(null), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatch_bookingConfirmed_noActiveConnection_skipsSyncJob() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .build()));
        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository, never()).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatch_bookingConfirmed_missingProjection_skipsSyncJob() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .build()));
        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository, never()).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatch_nonBooking_doesNotEnqueueSyncJob() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("User")
                .aggregateId(UUID.randomUUID())
                .eventType("USER_UPDATED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository, never()).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatch_bookingConfirmed_usesEventTypeAuthoritativeConnectionWhenPresent() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID authoritativeConnectionId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .eventTypeId(eventTypeId)
                        .build()));
        EventType configured = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Test")
                .slug("test")
                .duration(java.time.Duration.ofMinutes(30))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofMinutes(30))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(5))
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(authoritativeConnectionId)
                .projectionCalendarId("primary")
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId))
                .thenReturn(java.util.Optional.of(configured));
        when(calendarConnectionRepository.findById(authoritativeConnectionId))
                .thenReturn(java.util.Optional.of(connection(authoritativeConnectionId, CalendarProviderType.GOOGLE)));

        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("BOOKING"),
                eq(bookingId),
                eq("google"),
                eq("CREATE"),
                eq(null),
                eq(hostId),
                eq(authoritativeConnectionId),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatch_bookingConfirmed_googleMeet_defersImmediateNotificationDispatch() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .eventTypeId(eventTypeId)
                        .build()));
        EventType configured = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Google Meet")
                .slug("google-meet")
                .duration(java.time.Duration.ofMinutes(30))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofMinutes(30))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(5))
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(projectionConnectionId)
                .projectionCalendarId("primary")
                .conferencingProvider(ConferencingProviderType.GOOGLE_MEET)
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId))
                .thenReturn(java.util.Optional.of(configured));
        when(calendarConnectionRepository.findById(projectionConnectionId))
                .thenReturn(java.util.Optional.of(connection(projectionConnectionId, CalendarProviderType.GOOGLE)));

        dispatcher.dispatch(event);

        verify(bookingNotificationService, never()).handleOutboxEvent(event);
        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("BOOKING"),
                eq(bookingId),
                eq("google"),
                eq("CREATE"),
                eq(null),
                eq(hostId),
                eq(projectionConnectionId),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatch_sessionEvent_usesSessionCalendarSequenceAsOwnershipVersion() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID sessionSyncJobId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Session")
                .aggregateId(sessionId)
                .partitionKey(hostId)
                .eventType("REGISTRATION_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        when(eventSessionRepository.findById(sessionId))
                .thenReturn(java.util.Optional.of(EventSession.builder()
                        .id(sessionId)
                        .hostId(hostId)
                        .calendarSequence(7L)
                        .build()));
        CalendarSyncJobRepository.SessionSyncRow pendingRow = org.mockito.Mockito.mock(CalendarSyncJobRepository.SessionSyncRow.class);
        when(pendingRow.getSyncStatus()).thenReturn("PENDING");
        when(pendingRow.getExternalEventId()).thenReturn(null);
        when(pendingRow.getConferenceUrl()).thenReturn(null);
        when(pendingRow.getConferenceProvider()).thenReturn(null);
        when(pendingRow.getOwnershipVersion()).thenReturn(7L);
        CalendarSyncJobRepository.SessionSyncRow readyRow = org.mockito.Mockito.mock(CalendarSyncJobRepository.SessionSyncRow.class);
        when(readyRow.getSyncStatus()).thenReturn("SYNCED");
        when(readyRow.getExternalEventId()).thenReturn("ext-1");
        when(readyRow.getConferenceUrl()).thenReturn("https://meet.example.test/join");
        when(readyRow.getConferenceProvider()).thenReturn("GOOGLE_MEET");
        when(readyRow.getOwnershipVersion()).thenReturn(7L);
        when(calendarSyncJobRepository.findLatestSessionSyncRow(sessionId))
                .thenReturn(java.util.List.of(pendingRow), java.util.List.of(readyRow));
        when(calendarSyncJobRepository.findByInternalRefTypeAndInternalRefIdAndProvider(
                io.bunnycal.sync.state.InternalRefType.SESSION,
                sessionId,
                "DEFERRED"))
                .thenReturn(java.util.Optional.of(CalendarSyncJob.builder()
                        .id(sessionSyncJobId)
                        .build()));

        dispatcher.dispatch(event);

        verify(sessionSyncWorker).processJob(sessionSyncJobId);
        verify(sessionNotificationService).handleSessionOutboxEvent(event);
        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("SESSION"),
                eq(sessionId),
                eq("DEFERRED"),
                eq("UPDATE"),
                eq(null),
                eq(hostId),
                eq(null),
                eq(7L));
    }

    @Test
    void dispatch_sessionRegistrationConfirmed_defersNotificationUntilMetadataExists() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID sessionSyncJobId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Session")
                .aggregateId(sessionId)
                .partitionKey(hostId)
                .eventType("REGISTRATION_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        when(eventSessionRepository.findById(sessionId))
                .thenReturn(java.util.Optional.of(EventSession.builder()
                        .id(sessionId)
                        .hostId(hostId)
                        .calendarSequence(8L)
                        .build()));
        CalendarSyncJobRepository.SessionSyncRow pendingRow = org.mockito.Mockito.mock(CalendarSyncJobRepository.SessionSyncRow.class);
        when(pendingRow.getSyncStatus()).thenReturn("PENDING");
        when(pendingRow.getExternalEventId()).thenReturn(null);
        when(pendingRow.getConferenceUrl()).thenReturn(null);
        when(pendingRow.getConferenceProvider()).thenReturn(null);
        when(pendingRow.getOwnershipVersion()).thenReturn(8L);
        when(calendarSyncJobRepository.findLatestSessionSyncRow(sessionId))
                .thenReturn(java.util.List.of(pendingRow), java.util.List.of(pendingRow));
        when(calendarSyncJobRepository.findByInternalRefTypeAndInternalRefIdAndProvider(
                io.bunnycal.sync.state.InternalRefType.SESSION,
                sessionId,
                "DEFERRED"))
                .thenReturn(java.util.Optional.of(CalendarSyncJob.builder()
                        .id(sessionSyncJobId)
                        .build()));

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(event));

        verify(sessionSyncWorker).processJob(sessionSyncJobId);
        verify(sessionNotificationService, never()).handleSessionOutboxEvent(event);
    }

    private static CalendarConnection connection(UUID id, CalendarProviderType provider) {
        CalendarConnection connection = new CalendarConnection();
        connection.setProvider(provider);
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(connection, id);
        } catch (Exception ignored) {}
        return connection;
    }

    private static PlatformTransactionManager txManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // no-op
            }

            @Override
            public void rollback(TransactionStatus status) {
                // no-op
            }
        };
    }
}
