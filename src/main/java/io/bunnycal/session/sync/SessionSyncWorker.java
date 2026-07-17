package io.bunnycal.session.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.service.BookingSchedulingProjectionResolver;
import io.bunnycal.booking.service.BookingSubmissionFormatter;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.provider.CalendarProvider;
import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.CreateEventResponse;
import io.bunnycal.calendar.provider.DeleteEventRequest;
import io.bunnycal.calendar.provider.GoogleCalendarProvider;
import io.bunnycal.calendar.provider.MicrosoftCalendarProvider;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventResponse;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferenceDetails;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionRegistration;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.retry.SyncRetryPolicy;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SessionSyncWorker.class);

    private final CalendarSyncJobRepository syncJobRepository;
    private final EventSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final Map<CalendarProviderType, CalendarProvider> providersByType;
    private final BookingSubmissionFormatter bookingSubmissionFormatter;
    private final BookingSchedulingProjectionResolver projectionResolver;
    private final EventConferencingResolver conferencingResolver;
    private final SyncRetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;

    @Autowired
    public SessionSyncWorker(CalendarSyncJobRepository syncJobRepository,
                              EventSessionRepository sessionRepository,
                              SessionRegistrationRepository registrationRepository,
                              EventTypeRepository eventTypeRepository,
                              UserRepository userRepository,
                              CalendarConnectionRepository connectionRepository,
                              GoogleCalendarProvider googleCalendarProvider,
                              MicrosoftCalendarProvider microsoftCalendarProvider,
                              BookingSubmissionFormatter bookingSubmissionFormatter,
                              BookingSchedulingProjectionResolver projectionResolver,
                              EventConferencingResolver conferencingResolver,
                              SyncRetryPolicy retryPolicy,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry) {
        this.syncJobRepository = syncJobRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.providersByType = Map.of(
                CalendarProviderType.GOOGLE, googleCalendarProvider,
                CalendarProviderType.MICROSOFT, microsoftCalendarProvider
        );
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
        this.projectionResolver = projectionResolver;
        this.conferencingResolver = conferencingResolver;
        this.retryPolicy = retryPolicy;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SessionSyncWorker(CalendarSyncJobRepository syncJobRepository,
                             EventSessionRepository sessionRepository,
                             SessionRegistrationRepository registrationRepository,
                             EventTypeRepository eventTypeRepository,
                             UserRepository userRepository,
                             CalendarConnectionRepository connectionRepository,
                             GoogleCalendarProvider googleCalendarProvider,
                             MicrosoftCalendarProvider microsoftCalendarProvider,
                             BookingSchedulingProjectionResolver projectionResolver,
                             EventConferencingResolver conferencingResolver,
                             SyncRetryPolicy retryPolicy,
                             PlatformTransactionManager transactionManager,
                             MeterRegistry meterRegistry) {
        this(syncJobRepository, sessionRepository, registrationRepository, eventTypeRepository, userRepository,
                connectionRepository, googleCalendarProvider, microsoftCalendarProvider,
                new BookingSubmissionFormatter(new ObjectMapper()), projectionResolver, conferencingResolver,
                retryPolicy, transactionManager, meterRegistry);
    }

    public int processPending(int batchSize) {
        List<UUID> claimedIds = syncJobRepository.claimPendingBatchForSessions(Instant.now(), batchSize);
        if (claimedIds.isEmpty()) {
            log.debug("session_sync_batch_claim batchSize={} claimedCount=0", batchSize);
        } else {
            log.info("session_sync_batch_claim batchSize={} claimedCount={}", batchSize, claimedIds.size());
        }
        for (UUID id : claimedIds) {
            processJob(id);
        }
        return claimedIds.size();
    }

    public void processJob(UUID jobId) {
        if (jobId == null) {
            return;
        }
        try {
            txTemplate.executeWithoutResult(status -> {
                syncJobRepository.claimPendingSessionJobById(jobId);
                syncJobRepository.findById(jobId)
                        .filter(job -> {
                            if (job.getStatus() == SyncJobStatus.PROCESSING) {
                                return true;
                            }
                            if (job.getStatus() == SyncJobStatus.PENDING) {
                                log.warn("session_sync_skip_unclaimed_pending jobId={} sessionId={}",
                                        job.getId(), job.getInternalRefId());
                            }
                            return false;
                        })
                        .ifPresent(this::processOne);
            });
        } catch (RuntimeException ex) {
            meterRegistry.counter("session_sync_failure_count").increment();
            log.warn("session_sync_uncaught jobId={}", jobId, ex);
        }
    }

    private void processOne(CalendarSyncJob job) {
        UUID sessionId = job.getInternalRefId();
        MDC.put("sessionId", sessionId.toString());
        MDC.put("correlationId", job.getId().toString());
        try {
            EventSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                log.warn("session_sync_skip_missing sessionId={} jobId={}", sessionId, job.getId());
                syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
                return;
            }

            if (session.getCalendarSequence() != job.getOwnershipVersion()) {
                log.warn("session_sync_skip_stale_sequence sessionId={} jobId={} sessionSequence={} jobSequence={}",
                        sessionId, job.getId(), session.getCalendarSequence(), job.getOwnershipVersion());
                syncJobRepository.markSyncedFromProcessingWithLifecycle(
                        job.getId(),
                        job.getVersion(),
                        job.getExternalEventId(),
                        "STALE_SESSION_SEQUENCE");
                return;
            }

            EventType eventType = eventTypeRepository
                    .findByIdAndUserId(session.getEventTypeId(), session.getHostId())
                    .orElse(null);
            // Group sessions are written to the host's global write-back calendar, exactly like every
            // other kind. (They used to read a projection destination frozen on the event type — the
            // only kind that did, and the only one that had no fallback when it was absent.)
            BookingSchedulingProjectionResolver.SchedulingProjection projection = eventType == null
                    ? null
                    : projectionResolver.resolveForUser(session.getHostId());
            if (projection == null) {
                log.info("session_sync_skip_no_projection sessionId={} jobId={} hostId={}",
                        sessionId, job.getId(), session.getHostId());
                syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
                return;
            }

            CalendarConnection connection = connectionRepository
                    .findById(projection.connectionId())
                    .orElse(null);
            if (connection == null || connection.getStatus() != CalendarConnectionStatus.ACTIVE) {
                log.warn("session_sync_skip_inactive_connection sessionId={} jobId={}", sessionId, job.getId());
                syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
                return;
            }

            CalendarProvider provider = providersByType.get(connection.getProvider());
            if (provider == null) {
                log.warn("session_sync_skip_unsupported_provider provider={} sessionId={}", connection.getProvider(), sessionId);
                syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
                return;
            }

            User host = userRepository.findById(session.getHostId()).orElse(null);
            if (host == null) {
                log.warn("session_sync_skip_missing_host sessionId={} hostId={}", sessionId, session.getHostId());
                syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
                return;
            }

            List<SessionRegistration> confirmedRegistrations = registrationRepository.findConfirmedBySessionId(sessionId);

            String title = eventType.getName() != null && !eventType.getName().isBlank()
                    ? eventType.getName() : "Group Session";
            String description = bookingSubmissionFormatter.buildSessionDescription(confirmedRegistrations);
            String targetCalendarId = projection.calendarId();
            String idempotencyKey = "session-" + connection.getId() + "-" + session.getStartTime().getEpochSecond();
            ConferencingInstruction conferencingInstruction =
                    resolveConferencingInstruction(session.getHostId(), eventType);
            ConferenceDetails conferenceDetails = ConferenceDetails.fromInstruction(
                    conferencingInstruction, "session_event_type", Instant.now());

            boolean processed = switch (job.getDesiredAction()) {
                case CREATE -> {
                    processCreate(job, session, connection, provider,
                            title, description, host.getEmail(), confirmedRegistrations, targetCalendarId, idempotencyKey,
                            conferencingInstruction, conferenceDetails);
                    yield true;
                }
                case UPDATE -> {
                    processUpdate(job, session, connection, provider,
                            title, description, host.getEmail(), confirmedRegistrations, targetCalendarId, idempotencyKey,
                            conferencingInstruction, conferenceDetails);
                    yield true;
                }
                case DELETE -> processDelete(job, connection, provider);
            };
            if (!processed) {
                return;
            }
            meterRegistry.counter("session_sync_success_count").increment();
        } catch (CalendarClientException ex) {
            handleFailure(job, classify(ex));
        } catch (RuntimeException ex) {
            handleFailure(job, "PROVIDER_DOWN");
        } finally {
            MDC.remove("sessionId");
            MDC.remove("correlationId");
        }
    }

    private void processCreate(CalendarSyncJob job, EventSession session,
                                CalendarConnection connection, CalendarProvider provider,
                                String title, String description, String organizerEmail,
                                List<SessionRegistration> confirmedRegistrations, String targetCalendarId,
                                String idempotencyKey,
                                ConferencingInstruction conferencingInstruction,
                                ConferenceDetails conferenceDetails) {
        if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
            syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
            return;
        }
        if (confirmedRegistrations.isEmpty()) {
            log.info("session_sync_create_skip_no_attendees sessionId={}", session.getId());
            syncJobRepository.markSynced(job.getId(), job.getVersion(), null);
            return;
        }
        CreateEventResponse response = provider.createEvent(
                CreateEventRequest.forGroup(connection.getId(), title, description,
                        session.getStartTime(), session.getEndTime(),
                        organizerEmail, List.of(), idempotencyKey, targetCalendarId,
                        conferencingInstruction));
        ConferenceDetails resolvedConferenceDetails = conferenceDetails.withJoinUrlIfMissing(response.conferenceUrl(), "provider_create_result");
        int marked = syncJobRepository.markSyncedWithMetadata(
                job.getId(),
                job.getVersion(),
                response.externalEventId(),
                response.providerEventUrl(),
                resolvedConferenceDetails.joinUrl(),
                resolvedConferenceDetails.provider(),
                null);
        attachSessionExternalIdentityIfCompletionLostRace(job, response.externalEventId(), response.providerEventUrl(),
                resolvedConferenceDetails.joinUrl(), resolvedConferenceDetails.provider());
        if (marked == 0) {
            log.warn("session_sync_create_completion_lost_race sessionId={} jobId={} provider={} externalEventId={}",
                    session.getId(), job.getId(), connection.getProvider(), response.externalEventId());
        }
        log.info("session_sync_create_success sessionId={} provider={} externalEventId={}",
                session.getId(), connection.getProvider(), response.externalEventId());
    }

    private void processUpdate(CalendarSyncJob job, EventSession session,
                                CalendarConnection connection, CalendarProvider provider,
                                String title, String description, String organizerEmail,
                                List<SessionRegistration> confirmedRegistrations, String targetCalendarId,
                                String idempotencyKey,
                                ConferencingInstruction conferencingInstruction,
                                ConferenceDetails conferenceDetails) {
        SessionSyncProjectionState projectionState = findLatestProjectionState(job.getInternalRefId());
        String externalId = job.getExternalEventId();
        if (externalId == null || externalId.isBlank()) {
            externalId = projectionState.externalEventId();
        }
        if (externalId == null || externalId.isBlank()) {
            processCreate(job, session, connection, provider, title, description, organizerEmail,
                    confirmedRegistrations, targetCalendarId, idempotencyKey, conferencingInstruction, conferenceDetails);
            return;
        }
        if (confirmedRegistrations.isEmpty()) {
            processDelete(job, connection, provider);
            return;
        }
        ConferenceDetails persistedConferenceDetails = projectionState.conferenceDetails();
        ConferencingInstruction updateInstruction = suppressNativeConferenceRegeneration(
                conferencingInstruction,
                persistedConferenceDetails);
        ConferenceDetails authoritativeConferenceDetails = persistedConferenceDetails != null
                ? persistedConferenceDetails
                : conferenceDetails;
        UpdateEventResponse response = provider.updateEvent(
                UpdateEventRequest.forGroup(connection.getId(), externalId, title, description,
                        session.getStartTime(), session.getEndTime(),
                        organizerEmail, List.of(), targetCalendarId, updateInstruction));
        ConferenceDetails resolvedConferenceDetails = authoritativeConferenceDetails
                .withJoinUrlIfMissing(response.conferenceUrl(), "provider_update_result");
        int marked = syncJobRepository.markSyncedWithMetadata(
                job.getId(),
                job.getVersion(),
                response.externalEventId(),
                response.providerEventUrl(),
                resolvedConferenceDetails.joinUrl(),
                resolvedConferenceDetails.provider(),
                null);
        attachSessionExternalIdentityIfCompletionLostRace(job, response.externalEventId(), response.providerEventUrl(),
                resolvedConferenceDetails.joinUrl(), resolvedConferenceDetails.provider());
        if (marked == 0) {
            log.warn("session_sync_update_completion_lost_race sessionId={} jobId={} provider={} externalEventId={}",
                    session.getId(), job.getId(), connection.getProvider(), response.externalEventId());
        }
        log.info("session_sync_update_success sessionId={} provider={} externalEventId={}",
                session.getId(), connection.getProvider(), response.externalEventId());
    }

    private boolean processDelete(CalendarSyncJob job, CalendarConnection connection, CalendarProvider provider) {
        String externalId = job.getExternalEventId();
        if (externalId == null || externalId.isBlank()) {
            externalId = findLatestExternalEventId(job.getInternalRefId());
        }
        if (externalId == null || externalId.isBlank()) {
            log.error("session_sync_delete_missing_external_event_id sessionId={} jobId={} provider={}",
                    job.getInternalRefId(), job.getId(), connection.getProvider());
            syncJobRepository.markFailedPermanent(job.getId(), job.getVersion(), "MISSING_EXTERNAL_EVENT_ID");
            return false;
        }
        try {
            provider.deleteEvent(new DeleteEventRequest(connection.getId(), externalId));
        } catch (CalendarClientException ex) {
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                log.info("session_sync_delete_idempotent sessionId={} provider={} externalEventId={}",
                        job.getInternalRefId(), connection.getProvider(), externalId);
            } else {
                throw ex;
            }
        }
        syncJobRepository.markSynced(job.getId(), job.getVersion(), externalId);
        log.info("session_sync_delete_success sessionId={} provider={} externalEventId={}",
                job.getInternalRefId(), connection.getProvider(), externalId);
        return true;
    }

    private String findLatestExternalEventId(UUID sessionId) {
        return findLatestProjectionState(sessionId).externalEventId();
    }

    private SessionSyncProjectionState findLatestProjectionState(UUID sessionId) {
        return syncJobRepository.findLatestSessionSyncRow(sessionId).stream()
                .findFirst()
                .map(row -> new SessionSyncProjectionState(
                        blankToNull(row.getExternalEventId()),
                        conferenceDetailsFromRow(row)))
                .orElseGet(SessionSyncProjectionState::empty);
    }

    private static ConferenceDetails conferenceDetailsFromRow(CalendarSyncJobRepository.SessionSyncRow row) {
        String conferenceUrl = blankToNull(row.getConferenceUrl());
        String conferenceProvider = blankToNull(row.getConferenceProvider());
        if (conferenceUrl == null && conferenceProvider == null) {
            return null;
        }
        return new ConferenceDetails(
                conferenceProvider == null ? "NONE" : conferenceProvider,
                conferenceUrl,
                null,
                null,
                null,
                Map.of(),
                "session_sync_status",
                Instant.now());
    }

    private static ConferencingInstruction suppressNativeConferenceRegeneration(ConferencingInstruction requestedInstruction,
                                                                                ConferenceDetails persistedConferenceDetails) {
        if (requestedInstruction == null || !requestedInstruction.requestsNativeMeet()) {
            return requestedInstruction == null ? ConferencingInstruction.none() : requestedInstruction;
        }
        if (persistedConferenceDetails == null || persistedConferenceDetails.joinUrl() == null
                || persistedConferenceDetails.joinUrl().isBlank()) {
            return requestedInstruction;
        }
        return ConferencingInstruction.none();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void attachSessionExternalIdentityIfCompletionLostRace(CalendarSyncJob job,
                                                                   String externalEventId,
                                                                   String providerEventUrl,
                                                                   String conferenceUrl,
                                                                   String conferenceProvider) {
        if (externalEventId == null || externalEventId.isBlank()) {
            return;
        }
        syncJobRepository.attachSessionExternalEventMetadata(
                job.getInternalRefId(),
                job.getProvider(),
                externalEventId,
                providerEventUrl,
                conferenceUrl,
                conferenceProvider,
                null);
    }

    private void handleFailure(CalendarSyncJob job, String errorCode) {
        String code = errorCode == null ? "PROVIDER_ERROR" : errorCode;
        boolean permanent = isPermanent(code) || retryPolicy.isRetryExhausted(job.getAttemptCount() + 1);
        meterRegistry.counter("session_sync_failure_count").increment();
        syncJobRepository.markFailure(job.getId(), job.getVersion(),
                retryPolicy.nextRetryAt(job.getAttemptCount() + 1), code, permanent);
        log.warn("session_sync_failure sessionId={} jobId={} errorCode={} permanent={}",
                job.getInternalRefId(), job.getId(), code, permanent);
    }

    /**
     * @param hostId the session host — the writer, whose global default resolves the
     *               {@link ConferencingProviderType#DEFAULT} pointer. Without this the switch below
     *               would fall through {@code default -> none()} for every default-bound event type,
     *               and group sessions would quietly lose their join links.
     */
    private ConferencingInstruction resolveConferencingInstruction(UUID hostId, EventType eventType) {
        if (eventType == null) {
            return ConferencingInstruction.none();
        }
        ConferencingProviderType providerType = conferencingResolver.resolve(hostId, eventType);
        if (providerType == null || providerType == ConferencingProviderType.NONE) {
            return ConferencingInstruction.none();
        }
        return switch (providerType) {
            case GOOGLE_MEET, MICROSOFT_TEAMS -> ConferencingInstruction.requestNativeMeet(providerType);
            case CUSTOM_URL -> {
                String customUrl = eventType.getCustomConferenceUrl();
                if (customUrl == null || customUrl.isBlank()) {
                    yield ConferencingInstruction.none();
                }
                yield ConferencingInstruction.urlEmbedded(providerType, customUrl.trim(), null, null);
            }
            default -> ConferencingInstruction.none();
        };
    }

    private static boolean isPermanent(String errorCode) {
        return "INVALID_REQUEST".equals(errorCode) || "AUTH_REVOKED".equals(errorCode);
    }

    private static String classify(CalendarClientException ex) {
        int status = ex.getStatusCode();
        if (status == 401) return "AUTH_EXPIRED";
        if (status == 403) return "AUTH_REVOKED";
        if (status == 429) return "RATE_LIMIT";
        if (status >= 500) return "PROVIDER_DOWN";
        if (status >= 400) return "INVALID_REQUEST";
        return "PROVIDER_ERROR";
    }

    private record SessionSyncProjectionState(String externalEventId,
                                              ConferenceDetails conferenceDetails) {
        private static SessionSyncProjectionState empty() {
            return new SessionSyncProjectionState(null, null);
        }
    }
}
