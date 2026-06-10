package io.bunnycal.booking.outbox;

import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeLifecycleNotificationService;
import io.bunnycal.availability.service.EventTypeLifecycleOutboxPayload;
import io.bunnycal.booking.service.BookingEventTypeResolver;
import io.bunnycal.booking.ownership.BookingOwnershipService;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.notification.SessionNotificationService;
import io.bunnycal.session.sync.SessionSyncWorker;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.booking.notification.BookingNotificationService;
import io.bunnycal.team.notification.ParticipantSetupRequestNotificationService;
import io.bunnycal.team.notification.TeamInvitationNotificationService;
import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import io.bunnycal.sync.invariants.LineageContext;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LoggingOutboxEventDispatcher implements OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventDispatcher.class);
    private static final String BOOKING_AGGREGATE = "Booking";
    private static final String SESSION_AGGREGATE = "Session";

    private final CalendarSyncJobRepository calendarSyncJobRepository;
    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final EventSessionRepository eventSessionRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final BookingOwnershipService bookingOwnershipService;
    private final BookingEventTypeResolver bookingEventTypeResolver;
    @Nullable
    private final BookingNotificationService bookingNotificationService;
    @Nullable
    private final SessionNotificationService sessionNotificationService;
    @Nullable
    private final SessionSyncWorker sessionSyncWorker;
    @Nullable
    private final TeamInvitationNotificationService teamInvitationNotificationService;
    @Nullable
    private final ParticipantSetupRequestNotificationService setupRequestNotificationService;
    @Nullable
    private final EventTypeLifecycleNotificationService eventTypeLifecycleNotificationService;
    private final TransactionTemplate requiresNewTx;
    private final SyncInvariantMonitor invariantMonitor;
    private final MeterRegistry meterRegistry;

    public LoggingOutboxEventDispatcher(CalendarSyncJobRepository calendarSyncJobRepository,
                                        BookingRepository bookingRepository,
                                        EventTypeRepository eventTypeRepository,
                                        EventSessionRepository eventSessionRepository,
                                        CalendarConnectionRepository calendarConnectionRepository,
                                        BookingOwnershipService bookingOwnershipService,
                                        BookingEventTypeResolver bookingEventTypeResolver,
                                        @Nullable BookingNotificationService bookingNotificationService,
                                        @Nullable SessionNotificationService sessionNotificationService,
                                        @Nullable SessionSyncWorker sessionSyncWorker,
                                        @Nullable TeamInvitationNotificationService teamInvitationNotificationService,
                                        @Nullable ParticipantSetupRequestNotificationService setupRequestNotificationService,
                                        @Nullable EventTypeLifecycleNotificationService eventTypeLifecycleNotificationService,
                                        PlatformTransactionManager transactionManager,
                                        SyncInvariantMonitor invariantMonitor,
                                        MeterRegistry meterRegistry) {
        this.calendarSyncJobRepository = calendarSyncJobRepository;
        this.bookingRepository = bookingRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.bookingOwnershipService = bookingOwnershipService;
        this.bookingEventTypeResolver = bookingEventTypeResolver;
        this.bookingNotificationService = bookingNotificationService;
        this.sessionNotificationService = sessionNotificationService;
        this.sessionSyncWorker = sessionSyncWorker;
        this.teamInvitationNotificationService = teamInvitationNotificationService;
        this.setupRequestNotificationService = setupRequestNotificationService;
        this.eventTypeLifecycleNotificationService = eventTypeLifecycleNotificationService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.invariantMonitor = invariantMonitor;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        // TeamInvitation aggregate: send invitation email and return.
        if (TeamInvitationNotificationService.AGGREGATE_TYPE.equals(
                event != null ? event.getAggregateType() : null)) {
            if (teamInvitationNotificationService != null) {
                teamInvitationNotificationService.handleOutboxEvent(event);
            } else {
                log.info("team_invitation_notification_skip_disabled eventId={}", event != null ? event.getId() : null);
            }
            return;
        }

        // EventType aggregate: route to lifecycle notification service and return.
        if (EventTypeLifecycleOutboxPayload.AGGREGATE_TYPE.equals(
                event != null ? event.getAggregateType() : null)) {
            if (eventTypeLifecycleNotificationService != null) {
                eventTypeLifecycleNotificationService.handleOutboxEvent(event);
            } else {
                log.info("event_type_lifecycle_notification_skip_disabled eventId={}", event != null ? event.getId() : null);
            }
            return;
        }

        // ParticipantSetupRequest aggregate: send setup request email and return.
        if (ParticipantSetupRequestNotificationService.AGGREGATE_TYPE.equals(
                event != null ? event.getAggregateType() : null)) {
            if (setupRequestNotificationService != null) {
                setupRequestNotificationService.handleOutboxEvent(event);
            } else {
                log.info("setup_request_notification_skip_disabled eventId={}", event != null ? event.getId() : null);
            }
            return;
        }

        // Session aggregate: route to session notification + session sync job creation.
        if (SESSION_AGGREGATE.equals(event != null ? event.getAggregateType() : null)) {
            UUID sessionSyncJobId = createSessionSyncJobIfNeeded(event);
            if ("REGISTRATION_CONFIRMED".equals(event.getEventType())) {
                ensureSessionConfirmationNotificationReady(event, sessionSyncJobId);
            } else {
                if (sessionSyncWorker != null && sessionSyncJobId != null) {
                    sessionSyncWorker.processJob(sessionSyncJobId);
                }
                if (sessionNotificationService != null) {
                    sessionNotificationService.handleSessionOutboxEvent(event);
                }
            }
            return;
        }

        if (bookingNotificationService != null && !shouldDeferNotificationUntilProjection(event)) {
            bookingNotificationService.handleOutboxEvent(event);
        }

        if (isBookingSyncCandidate(event)) {
            String desiredAction = mapDesiredAction(event.getEventType());
            if (desiredAction != null) {
                UUID partitionKey = event.getPartitionKey();
                if (partitionKey == null) {
                    partitionKey = bookingRepository.findAnyById(event.getAggregateId())
                            .map(booking -> booking.getHostId())
                            .orElse(null);
                }
                SchedulingResolution resolution = resolveSchedulingConnection(event.getAggregateId(), partitionKey);
                if (resolution == null && partitionKey != null) {
                    log.warn("outbox.sync_job_skipped_missing_projection_ownership bookingId={} hostId={} action={}",
                            event.getAggregateId(), partitionKey, desiredAction);
                    meterRegistry.counter("sync_jobs_skipped_missing_ownership_total").increment();
                    return;
                }
                io.bunnycal.booking.ownership.BookingOwnership ownership = bookingRepository.findAnyById(event.getAggregateId())
                        .map(booking -> bookingOwnershipService.ensureOwnership(
                                booking,
                                bookingEventTypeResolver.requireForBooking(booking)))
                        .orElse(null);
                String resolvedProvider = resolution != null ? resolution.provider() : null;
                UUID schedulingConnectionId = resolution != null ? resolution.connectionId() : null;
                if (partitionKey != null && resolvedProvider != null) {
                    bookingRepository.stampSchedulingProvider(event.getAggregateId(), partitionKey, resolvedProvider);
                }
                calendarSyncJobRepository.upsertPendingJob(
                        UUID.randomUUID(),
                        "BOOKING",
                        event.getAggregateId(),
                        resolvedProvider,
                        desiredAction,
                        null,
                        partitionKey,
                        schedulingConnectionId,
                        ownership == null ? 1L : ownership.getOwnershipVersion()
                );
                log.info("outbox.sync_job_created id={} bookingId={} provider={} action={}",
                        event.getId(), event.getAggregateId(), resolvedProvider, desiredAction);
                emitSyncEnqueueInvariant(event, desiredAction);
                return;
            }
        }

        log.info("outbox.dispatch id={} type={} aggregateType={} aggregateId={}",
                event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId());
    }

    private static boolean isBookingSyncCandidate(OutboxEvent event) {
        return event != null
                && BOOKING_AGGREGATE.equals(event.getAggregateType())
                && event.getAggregateId() != null;
    }

    private boolean shouldDeferNotificationUntilProjection(OutboxEvent event) {
        if (event == null
                || !"Booking".equals(event.getAggregateType())
                || event.getAggregateId() == null
                || !"BOOKING_CONFIRMED".equals(event.getEventType())) {
            return false;
        }
        return bookingRepository.findAnyById(event.getAggregateId())
                .map(bookingEventTypeResolver::requireForBooking)
                .map(eventType -> eventType.getConferencingProvider() == ConferencingProviderType.GOOGLE_MEET)
                .orElse(false);
    }

    private record SchedulingResolution(UUID connectionId, String provider) {}

    @Nullable
    private SchedulingResolution resolveSchedulingConnection(UUID bookingId, @Nullable UUID hostId) {
        return bookingRepository.findAnyById(bookingId)
                .map(booking -> bookingOwnershipService.ensureOwnership(
                        booking,
                        bookingEventTypeResolver.requireForBooking(booking)))
                .flatMap(ownership -> ownership.getProjectionConnectionId() == null || ownership.getProjectionProvider() == null
                        ? java.util.Optional.empty()
                        : calendarConnectionRepository.findById(ownership.getProjectionConnectionId())
                                .map(c -> new SchedulingResolution(
                                        c.getId(),
                                        ownership.getProjectionProvider().name().toLowerCase(java.util.Locale.ROOT))))
                .orElse(null);
    }

    private static String mapDesiredAction(String eventType) {
        if ("BOOKING_CONFIRMED".equals(eventType)) {
            return "CREATE";
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "UPDATE";
        }
        if ("BOOKING_CANCELLED".equals(eventType)) {
            return "DELETE";
        }
        return null;
    }

    @Nullable
    private UUID createSessionSyncJobIfNeeded(OutboxEvent event) {
        if (event == null || event.getAggregateId() == null) return null;
        String desiredAction = mapSessionDesiredAction(event.getEventType());
        if (desiredAction == null) return null;
        UUID sessionId = event.getAggregateId();
        UUID hostId = event.getPartitionKey();
        SessionSyncResolution resolution = resolveSessionSyncResolution(sessionId);
        long sessionSequence = resolution.sessionSequence();
        requiresNewTx.executeWithoutResult(status ->
                calendarSyncJobRepository.upsertPendingJob(
                        UUID.randomUUID(),
                        "SESSION",
                        sessionId,
                        resolution.provider(),
                        desiredAction,
                        null,
                        hostId,
                        null,
                        sessionSequence
                )
        );
        log.info("outbox.session_sync_job_created sessionId={} action={} sessionSequence={}",
                sessionId, desiredAction, sessionSequence);
        return calendarSyncJobRepository.findByInternalRefTypeAndInternalRefIdAndProvider(
                io.bunnycal.sync.state.InternalRefType.SESSION,
                sessionId,
                resolution.provider())
                .map(job -> job.getId())
                .orElse(null);
    }

    private void ensureSessionConfirmationNotificationReady(OutboxEvent event, UUID sessionSyncJobId) {
        if (sessionNotificationService == null) {
            throw new IllegalStateException("session notification service unavailable for confirmation event");
        }
        if (sessionSyncWorker == null || sessionSyncJobId == null) {
            throw new IllegalStateException("session sync worker unavailable for confirmation event");
        }

        SessionOutboxReadiness readinessBeforeSync = readSessionConfirmationReadiness(event.getAggregateId());
        if (!readinessBeforeSync.ready()) {
            sessionSyncWorker.processJob(sessionSyncJobId);
        }

        SessionOutboxReadiness readinessAfterSync = readSessionConfirmationReadiness(event.getAggregateId());
        if (!readinessAfterSync.ready()) {
            log.warn("outbox.session_confirmation_deferred_missing_conference_metadata sessionId={} jobId={} state={} externalEventId={} conferenceUrl={} conferenceProvider={}",
                    event.getAggregateId(),
                    sessionSyncJobId,
                    readinessAfterSync.syncStatus(),
                    readinessAfterSync.externalEventId(),
                    readinessAfterSync.conferenceUrl(),
                    readinessAfterSync.conferenceProvider());
            throw new IllegalStateException("session confirmation notification deferred until conference metadata is available");
        }

        sessionNotificationService.handleSessionOutboxEvent(event);
    }

    private SessionOutboxReadiness readSessionConfirmationReadiness(UUID sessionId) {
        return calendarSyncJobRepository.findLatestSessionSyncRow(sessionId).stream()
                .findFirst()
                .map(row -> new SessionOutboxReadiness(
                        row.getSyncStatus(),
                        row.getExternalEventId(),
                        row.getConferenceUrl(),
                        row.getConferenceProvider(),
                        row.getOwnershipVersion()))
                .orElseGet(() -> new SessionOutboxReadiness(null, null, null, null, null));
    }

    private record SessionOutboxReadiness(String syncStatus,
                                          String externalEventId,
                                          String conferenceUrl,
                                          String conferenceProvider,
                                          Long ownershipVersion) {
        boolean ready() {
            return SyncJobStatus.SYNCED.name().equals(syncStatus)
                    && externalEventId != null && !externalEventId.isBlank()
                    && conferenceUrl != null && !conferenceUrl.isBlank()
                    && conferenceProvider != null && !conferenceProvider.isBlank();
        }
    }

    private SessionSyncResolution resolveSessionSyncResolution(UUID sessionId) {
        EventSession session = eventSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return new SessionSyncResolution("DEFERRED", 0L);
        }
        String provider = eventTypeRepository.findByIdAndUserId(session.getEventTypeId(), session.getHostId())
                .map(eventType -> eventType.getProjectionProvider() == null
                        ? "DEFERRED"
                        : eventType.getProjectionProvider().name().toLowerCase(java.util.Locale.ROOT))
                .orElse("DEFERRED");
        return new SessionSyncResolution(provider, session.getCalendarSequence());
    }

    private record SessionSyncResolution(String provider, long sessionSequence) {}

    private static String mapSessionDesiredAction(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "REGISTRATION_CONFIRMED" -> "UPDATE";
            case "REGISTRATION_CANCELLED" -> "UPDATE";
            case "SESSION_CANCELLED" -> "DELETE";
            case "SESSION_RESCHEDULED" -> "UPDATE";
            default -> null;
        };
    }

    private void emitSyncEnqueueInvariant(OutboxEvent event, String desiredAction) {
        BookingState bookingState = switch (desiredAction) {
            case "DELETE" -> BookingState.CANCELLED;
            case "UPDATE" -> BookingState.CONFIRMED;
            case "CREATE" -> BookingState.CONFIRMED;
            default -> BookingState.PENDING;
        };
        invariantMonitor.assertState(
                "sync_enqueue_transition",
                bookingState,
                SyncJobStatus.PENDING,
                bookingState == BookingState.CANCELLED
                        ? CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT
                        : CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION,
                new LineageContext(
                        String.valueOf(event.getId()),
                        String.valueOf(event.getId()),
                        String.valueOf(event.getAggregateId()),
                        "",
                        "",
                        ""));
    }
}
