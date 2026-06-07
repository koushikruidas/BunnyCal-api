package io.bunnycal.session.sync;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.CreateEventResponse;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventResponse;
import io.bunnycal.calendar.provider.GoogleCalendarProvider;
import io.bunnycal.calendar.provider.MicrosoftCalendarProvider;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.retry.SyncRetryPolicy;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.InternalRefType;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.bunnycal.auth.domain.user.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SessionSyncWorkerTest {

    @Test
    void processJob_claimsPendingSessionJobBeforeProcessing() {
        CalendarSyncJobRepository syncJobRepository = org.mockito.Mockito.mock(CalendarSyncJobRepository.class);
        EventSessionRepository sessionRepository = org.mockito.Mockito.mock(EventSessionRepository.class);
        SessionRegistrationRepository registrationRepository = org.mockito.Mockito.mock(SessionRegistrationRepository.class);
        EventTypeRepository eventTypeRepository = org.mockito.Mockito.mock(EventTypeRepository.class);
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        CalendarConnectionRepository connectionRepository = org.mockito.Mockito.mock(CalendarConnectionRepository.class);
        GoogleCalendarProvider googleCalendarProvider = org.mockito.Mockito.mock(GoogleCalendarProvider.class);
        MicrosoftCalendarProvider microsoftCalendarProvider = org.mockito.Mockito.mock(MicrosoftCalendarProvider.class);
        SyncRetryPolicy retryPolicy = org.mockito.Mockito.mock(SyncRetryPolicy.class);

        SessionSyncWorker worker = new SessionSyncWorker(
                syncJobRepository,
                sessionRepository,
                registrationRepository,
                eventTypeRepository,
                userRepository,
                connectionRepository,
                googleCalendarProvider,
                microsoftCalendarProvider,
                retryPolicy,
                txManager(),
                new SimpleMeterRegistry());

        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        when(syncJobRepository.claimPendingSessionJobById(jobId)).thenReturn(1);
        when(syncJobRepository.findById(jobId)).thenReturn(Optional.of(CalendarSyncJob.builder()
                .id(jobId)
                .internalRefType(InternalRefType.SESSION)
                .internalRefId(sessionId)
                .partitionKey(hostId)
                .ownershipVersion(1L)
                .provider("google")
                .desiredAction(SyncDesiredAction.UPDATE)
                .status(SyncJobStatus.PROCESSING)
                .externalEventId(null)
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .version(2L)
                .build()));

        EventType eventType = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Group Session")
                .slug("group-session")
                .duration(java.time.Duration.ofHours(1))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofHours(1))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(15))
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(connectionId)
                .projectionCalendarId("primary")
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(User.builder()
                .id(hostId)
                .email("host@example.test")
                .username("hostuser")
                .name("Host")
                .timezone("UTC")
                .build()));
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, CalendarProviderType.GOOGLE)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(EventSession.builder()
                .id(sessionId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-06-15T09:00:00Z"))
                .endTime(Instant.parse("2026-06-15T10:00:00Z"))
                .status(SessionStatus.OPEN)
                .capacity(5)
                .confirmedCount(1)
                .calendarSequence(1L)
                .build()));
        when(registrationRepository.findConfirmedBySessionId(sessionId))
                .thenReturn(List.of(registration(sessionId, hostId, "guest@example.test", "Guest")));
        when(googleCalendarProvider.createEvent(org.mockito.ArgumentMatchers.any(CreateEventRequest.class)))
                .thenReturn(new CreateEventResponse("ext-1", "provider-url", "https://meet.google.com/test-link"));

        worker.processJob(jobId);

        verify(syncJobRepository).claimPendingSessionJobById(jobId);
        verify(syncJobRepository).markSyncedWithMetadata(
                jobId,
                2L,
                "ext-1",
                "provider-url",
                "https://meet.google.com/test-link",
                "GOOGLE_MEET",
                null);
    }

    @Test
    void processPending_skipsStaleSequenceJobs() {
        CalendarSyncJobRepository syncJobRepository = org.mockito.Mockito.mock(CalendarSyncJobRepository.class);
        EventSessionRepository sessionRepository = org.mockito.Mockito.mock(EventSessionRepository.class);
        SessionRegistrationRepository registrationRepository = org.mockito.Mockito.mock(SessionRegistrationRepository.class);
        EventTypeRepository eventTypeRepository = org.mockito.Mockito.mock(EventTypeRepository.class);
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        CalendarConnectionRepository connectionRepository = org.mockito.Mockito.mock(CalendarConnectionRepository.class);
        GoogleCalendarProvider googleCalendarProvider = org.mockito.Mockito.mock(GoogleCalendarProvider.class);
        MicrosoftCalendarProvider microsoftCalendarProvider = org.mockito.Mockito.mock(MicrosoftCalendarProvider.class);
        SyncRetryPolicy retryPolicy = org.mockito.Mockito.mock(SyncRetryPolicy.class);

        SessionSyncWorker worker = new SessionSyncWorker(
                syncJobRepository,
                sessionRepository,
                registrationRepository,
                eventTypeRepository,
                userRepository,
                connectionRepository,
                googleCalendarProvider,
                microsoftCalendarProvider,
                retryPolicy,
                txManager(),
                new SimpleMeterRegistry());

        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        CalendarSyncJob job = CalendarSyncJob.builder()
                .id(jobId)
                .internalRefType(InternalRefType.SESSION)
                .internalRefId(sessionId)
                .partitionKey(hostId)
                .schedulingConnectionId(UUID.randomUUID())
                .ownershipVersion(3L)
                .provider("DEFERRED")
                .desiredAction(SyncDesiredAction.UPDATE)
                .status(SyncJobStatus.PROCESSING)
                .externalEventId("external-123")
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .version(11L)
                .build();

        when(syncJobRepository.claimPendingBatchForSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(jobId));
        when(syncJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(EventSession.builder()
                .id(sessionId)
                .hostId(hostId)
                .eventTypeId(UUID.randomUUID())
                .startTime(Instant.parse("2026-06-15T09:00:00Z"))
                .endTime(Instant.parse("2026-06-15T10:00:00Z"))
                .status(SessionStatus.OPEN)
                .capacity(10)
                .confirmedCount(2)
                .calendarSequence(4L)
                .build()));

        worker.processPending(10);

        verify(syncJobRepository).markSyncedFromProcessingWithLifecycle(
                jobId,
                11L,
                "external-123",
                "STALE_SESSION_SEQUENCE");
        verify(googleCalendarProvider, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(microsoftCalendarProvider, never()).createEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void processPending_firstAttendeeCreatesLaterAttendeesUpdateSameExternalEvent() {
        CalendarSyncJobRepository syncJobRepository = org.mockito.Mockito.mock(CalendarSyncJobRepository.class);
        EventSessionRepository sessionRepository = org.mockito.Mockito.mock(EventSessionRepository.class);
        SessionRegistrationRepository registrationRepository = org.mockito.Mockito.mock(SessionRegistrationRepository.class);
        EventTypeRepository eventTypeRepository = org.mockito.Mockito.mock(EventTypeRepository.class);
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        CalendarConnectionRepository connectionRepository = org.mockito.Mockito.mock(CalendarConnectionRepository.class);
        GoogleCalendarProvider googleCalendarProvider = org.mockito.Mockito.mock(GoogleCalendarProvider.class);
        MicrosoftCalendarProvider microsoftCalendarProvider = org.mockito.Mockito.mock(MicrosoftCalendarProvider.class);
        SyncRetryPolicy retryPolicy = org.mockito.Mockito.mock(SyncRetryPolicy.class);

        SessionSyncWorker worker = new SessionSyncWorker(
                syncJobRepository,
                sessionRepository,
                registrationRepository,
                eventTypeRepository,
                userRepository,
                connectionRepository,
                googleCalendarProvider,
                microsoftCalendarProvider,
                retryPolicy,
                txManager(),
                new SimpleMeterRegistry());

        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID jobCreateId = UUID.randomUUID();
        UUID jobUpdateId = UUID.randomUUID();
        String externalEventId = "ext-1";

        EventType eventType = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Group Session")
                .slug("group-session")
                .duration(java.time.Duration.ofHours(1))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofHours(1))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(15))
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(connectionId)
                .projectionCalendarId("primary")
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(User.builder()
                .id(hostId)
                .email("host@example.test")
                .username("hostuser")
                .name("Host")
                .timezone("UTC")
                .build()));
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, CalendarProviderType.GOOGLE)));

        when(syncJobRepository.claimPendingBatchForSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(jobCreateId), List.of(jobUpdateId));
        when(syncJobRepository.findById(jobCreateId)).thenReturn(Optional.of(CalendarSyncJob.builder()
                .id(jobCreateId)
                .internalRefType(InternalRefType.SESSION)
                .internalRefId(sessionId)
                .partitionKey(hostId)
                .schedulingConnectionId(connectionId)
                .ownershipVersion(1L)
                .provider("DEFERRED")
                .desiredAction(SyncDesiredAction.UPDATE)
                .status(SyncJobStatus.PROCESSING)
                .externalEventId(null)
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .version(1L)
                .build()));
        when(syncJobRepository.findById(jobUpdateId)).thenReturn(Optional.of(CalendarSyncJob.builder()
                .id(jobUpdateId)
                .internalRefType(InternalRefType.SESSION)
                .internalRefId(sessionId)
                .partitionKey(hostId)
                .schedulingConnectionId(connectionId)
                .ownershipVersion(2L)
                .provider("DEFERRED")
                .desiredAction(SyncDesiredAction.UPDATE)
                .status(SyncJobStatus.PROCESSING)
                .externalEventId(externalEventId)
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .version(2L)
                .build()));

        when(sessionRepository.findById(sessionId))
                .thenReturn(
                        Optional.of(EventSession.builder()
                                .id(sessionId)
                                .hostId(hostId)
                                .eventTypeId(eventTypeId)
                                .startTime(Instant.parse("2026-06-15T09:00:00Z"))
                                .endTime(Instant.parse("2026-06-15T10:00:00Z"))
                                .status(SessionStatus.OPEN)
                                .capacity(10)
                                .confirmedCount(1)
                                .calendarSequence(1L)
                                .build()),
                        Optional.of(EventSession.builder()
                                .id(sessionId)
                                .hostId(hostId)
                                .eventTypeId(eventTypeId)
                                .startTime(Instant.parse("2026-06-15T09:00:00Z"))
                                .endTime(Instant.parse("2026-06-15T10:00:00Z"))
                                .status(SessionStatus.OPEN)
                                .capacity(10)
                                .confirmedCount(2)
                                .calendarSequence(2L)
                                .build()));

        when(registrationRepository.findConfirmedBySessionId(sessionId))
                .thenReturn(
                        List.of(registration(sessionId, hostId, "a@example.com", "A")),
                        List.of(
                                registration(sessionId, hostId, "a@example.com", "A"),
                                registration(sessionId, hostId, "b@example.com", "B")));
        when(googleCalendarProvider.createEvent(org.mockito.ArgumentMatchers.any(CreateEventRequest.class)))
                .thenReturn(new CreateEventResponse(externalEventId, "provider-url-1", "conference-url-1"));
        when(googleCalendarProvider.updateEvent(org.mockito.ArgumentMatchers.any(UpdateEventRequest.class)))
                .thenReturn(new UpdateEventResponse(externalEventId, "provider-url-2", "conference-url-2"));

        worker.processPending(10);
        worker.processPending(10);

        ArgumentCaptor<CreateEventRequest> createCaptor = ArgumentCaptor.forClass(CreateEventRequest.class);
        ArgumentCaptor<UpdateEventRequest> updateCaptor = ArgumentCaptor.forClass(UpdateEventRequest.class);
        verify(googleCalendarProvider).createEvent(createCaptor.capture());
        verify(googleCalendarProvider).updateEvent(updateCaptor.capture());

        org.assertj.core.api.Assertions.assertThat(createCaptor.getValue().isMultiAttendee()).isTrue();
        org.assertj.core.api.Assertions.assertThat(createCaptor.getValue().attendees()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(updateCaptor.getValue().externalEventId()).isEqualTo(externalEventId);
        org.assertj.core.api.Assertions.assertThat(updateCaptor.getValue().attendees()).hasSize(2);
    }

    @Test
    void processPending_deleteFallsBackToLatestSessionSyncExternalEventId() {
        CalendarSyncJobRepository syncJobRepository = org.mockito.Mockito.mock(CalendarSyncJobRepository.class);
        EventSessionRepository sessionRepository = org.mockito.Mockito.mock(EventSessionRepository.class);
        SessionRegistrationRepository registrationRepository = org.mockito.Mockito.mock(SessionRegistrationRepository.class);
        EventTypeRepository eventTypeRepository = org.mockito.Mockito.mock(EventTypeRepository.class);
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        CalendarConnectionRepository connectionRepository = org.mockito.Mockito.mock(CalendarConnectionRepository.class);
        GoogleCalendarProvider googleCalendarProvider = org.mockito.Mockito.mock(GoogleCalendarProvider.class);
        MicrosoftCalendarProvider microsoftCalendarProvider = org.mockito.Mockito.mock(MicrosoftCalendarProvider.class);
        SyncRetryPolicy retryPolicy = org.mockito.Mockito.mock(SyncRetryPolicy.class);

        SessionSyncWorker worker = new SessionSyncWorker(
                syncJobRepository,
                sessionRepository,
                registrationRepository,
                eventTypeRepository,
                userRepository,
                connectionRepository,
                googleCalendarProvider,
                microsoftCalendarProvider,
                retryPolicy,
                txManager(),
                new SimpleMeterRegistry());

        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String externalEventId = "ext-delete";

        EventType eventType = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Group Session")
                .slug("group-session")
                .duration(java.time.Duration.ofHours(1))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofHours(1))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(15))
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(connectionId)
                .projectionCalendarId("primary")
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(User.builder()
                .id(hostId)
                .email("host@example.test")
                .username("hostuser")
                .name("Host")
                .timezone("UTC")
                .build()));
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, CalendarProviderType.GOOGLE)));

        when(syncJobRepository.claimPendingBatchForSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(jobId));
        when(syncJobRepository.findById(jobId)).thenReturn(Optional.of(CalendarSyncJob.builder()
                .id(jobId)
                .internalRefType(InternalRefType.SESSION)
                .internalRefId(sessionId)
                .partitionKey(hostId)
                .schedulingConnectionId(connectionId)
                .ownershipVersion(1L)
                .provider("google")
                .desiredAction(SyncDesiredAction.DELETE)
                .status(SyncJobStatus.PROCESSING)
                .externalEventId(null)
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .version(5L)
                .build()));

        CalendarSyncJobRepository.SessionSyncRow row = org.mockito.Mockito.mock(CalendarSyncJobRepository.SessionSyncRow.class);
        when(row.getExternalEventId()).thenReturn(externalEventId);
        when(syncJobRepository.findLatestSessionSyncRow(sessionId)).thenReturn(List.of(row));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(EventSession.builder()
                .id(sessionId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-06-15T09:00:00Z"))
                .endTime(Instant.parse("2026-06-15T10:00:00Z"))
                .status(SessionStatus.CANCELLED)
                .capacity(10)
                .confirmedCount(0)
                .calendarSequence(1L)
                .build()));
        when(registrationRepository.findConfirmedBySessionId(sessionId)).thenReturn(List.of());

        worker.processPending(10);

        verify(googleCalendarProvider).deleteEvent(org.mockito.ArgumentMatchers.argThat(req ->
                req != null && connectionId.equals(req.connectionId()) && externalEventId.equals(req.externalEventId())));
        verify(syncJobRepository).markSynced(jobId, 5L, externalEventId);
    }

    @Test
    void processPending_deleteFailsWhenExternalEventIdMissing() {
        CalendarSyncJobRepository syncJobRepository = org.mockito.Mockito.mock(CalendarSyncJobRepository.class);
        EventSessionRepository sessionRepository = org.mockito.Mockito.mock(EventSessionRepository.class);
        SessionRegistrationRepository registrationRepository = org.mockito.Mockito.mock(SessionRegistrationRepository.class);
        EventTypeRepository eventTypeRepository = org.mockito.Mockito.mock(EventTypeRepository.class);
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        CalendarConnectionRepository connectionRepository = org.mockito.Mockito.mock(CalendarConnectionRepository.class);
        GoogleCalendarProvider googleCalendarProvider = org.mockito.Mockito.mock(GoogleCalendarProvider.class);
        MicrosoftCalendarProvider microsoftCalendarProvider = org.mockito.Mockito.mock(MicrosoftCalendarProvider.class);
        SyncRetryPolicy retryPolicy = org.mockito.Mockito.mock(SyncRetryPolicy.class);

        SessionSyncWorker worker = new SessionSyncWorker(
                syncJobRepository,
                sessionRepository,
                registrationRepository,
                eventTypeRepository,
                userRepository,
                connectionRepository,
                googleCalendarProvider,
                microsoftCalendarProvider,
                retryPolicy,
                txManager(),
                new SimpleMeterRegistry());

        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        EventType eventType = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Group Session")
                .slug("group-session")
                .duration(java.time.Duration.ofHours(1))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofHours(1))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(15))
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(connectionId)
                .projectionCalendarId("primary")
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(User.builder()
                .id(hostId)
                .email("host@example.test")
                .username("hostuser")
                .name("Host")
                .timezone("UTC")
                .build()));
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, CalendarProviderType.GOOGLE)));

        when(syncJobRepository.claimPendingBatchForSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(jobId));
        when(syncJobRepository.findById(jobId)).thenReturn(Optional.of(CalendarSyncJob.builder()
                .id(jobId)
                .internalRefType(InternalRefType.SESSION)
                .internalRefId(sessionId)
                .partitionKey(hostId)
                .schedulingConnectionId(connectionId)
                .ownershipVersion(1L)
                .provider("google")
                .desiredAction(SyncDesiredAction.DELETE)
                .status(SyncJobStatus.PROCESSING)
                .externalEventId(null)
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .version(9L)
                .build()));
        when(syncJobRepository.findLatestSessionSyncRow(sessionId)).thenReturn(List.of());
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(EventSession.builder()
                .id(sessionId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-06-15T09:00:00Z"))
                .endTime(Instant.parse("2026-06-15T10:00:00Z"))
                .status(SessionStatus.CANCELLED)
                .capacity(10)
                .confirmedCount(0)
                .calendarSequence(1L)
                .build()));
        when(registrationRepository.findConfirmedBySessionId(sessionId)).thenReturn(List.of());

        worker.processPending(10);

        verify(syncJobRepository).markFailedPermanent(jobId, 9L, "MISSING_EXTERNAL_EVENT_ID");
        verify(googleCalendarProvider, never()).deleteEvent(org.mockito.ArgumentMatchers.any());
        verify(microsoftCalendarProvider, never()).deleteEvent(org.mockito.ArgumentMatchers.any());
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

    private static io.bunnycal.session.domain.SessionRegistration registration(UUID sessionId, UUID hostId, String email, String name) {
        return io.bunnycal.session.domain.SessionRegistration.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .hostId(hostId)
                .guestEmail(email)
                .guestName(name)
                .status(io.bunnycal.session.domain.RegistrationStatus.CONFIRMED)
                .build();
    }

    private static PlatformTransactionManager txManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
