package io.bunnycal.booking.outbox;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.availability.service.EventTypeLifecycleNotificationService;
import io.bunnycal.availability.service.EventTypeLifecycleOutboxPayload;
import io.bunnycal.booking.service.BookingEventTypeResolver;
import io.bunnycal.booking.service.BookingSchedulingProjectionResolver;
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
import io.bunnycal.conferencing.service.EventConferencingResolver;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import io.bunnycal.sync.invariants.LineageContext;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
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
    private final EventConferencingResolver conferencingResolver;
    private final BookingSchedulingProjectionResolver projectionResolver;
    @Nullable
    private final BookingNotificationService bookingNotificationService;
    @Nullable
    private final SessionNotificationService sessionNotificationService;
    @Nullable
    private final SessionSyncWorker sessionSyncWorker;
    /** Lazy: SessionService publishes to the outbox, so eager injection would cycle. */
    private final org.springframework.beans.factory.ObjectProvider<io.bunnycal.session.service.SessionService> sessionServiceProvider;
    @Nullable
    private final TeamInvitationNotificationService teamInvitationNotificationService;
    @Nullable
    private final ParticipantSetupRequestNotificationService setupRequestNotificationService;
    @Nullable
    private final EventTypeLifecycleNotificationService eventTypeLifecycleNotificationService;
    @Nullable
    private final io.bunnycal.billing.notification.BillingNotificationService billingNotificationService;
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
                                        EventConferencingResolver conferencingResolver,
                                        BookingSchedulingProjectionResolver projectionResolver,
                                        @Nullable BookingNotificationService bookingNotificationService,
                                        @Nullable SessionNotificationService sessionNotificationService,
                                        @Nullable SessionSyncWorker sessionSyncWorker,
                                        org.springframework.beans.factory.ObjectProvider<io.bunnycal.session.service.SessionService> sessionServiceProvider,
                                        @Nullable TeamInvitationNotificationService teamInvitationNotificationService,
                                        @Nullable ParticipantSetupRequestNotificationService setupRequestNotificationService,
                                        @Nullable EventTypeLifecycleNotificationService eventTypeLifecycleNotificationService,
                                        @Nullable io.bunnycal.billing.notification.BillingNotificationService billingNotificationService,
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
        this.conferencingResolver = conferencingResolver;
        this.projectionResolver = projectionResolver;
        this.bookingNotificationService = bookingNotificationService;
        this.sessionNotificationService = sessionNotificationService;
        this.sessionSyncWorker = sessionSyncWorker;
        this.sessionServiceProvider = sessionServiceProvider;
        this.teamInvitationNotificationService = teamInvitationNotificationService;
        this.setupRequestNotificationService = setupRequestNotificationService;
        this.eventTypeLifecycleNotificationService = eventTypeLifecycleNotificationService;
        this.billingNotificationService = billingNotificationService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.invariantMonitor = invariantMonitor;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        // TeamInvitation aggregate: send invitation email and return.
        if (TeamInvitationNotificationService.AGGREGATE_TYPE_INVITATION.equals(
                event != null ? event.getAggregateType() : null)
                || TeamInvitationNotificationService.AGGREGATE_TYPE_MEMBER.equals(
                event != null ? event.getAggregateType() : null)) {
            if (teamInvitationNotificationService != null) {
                teamInvitationNotificationService.handleOutboxEvent(event);
            } else {
                log.info("team_invitation_notification_skip_disabled eventId={}", event != null ? event.getId() : null);
            }
            return;
        }

        // Billing aggregates (Subscription / Invoice): route to billing notifications.
        if (io.bunnycal.billing.notification.BillingNotificationService.supportsAggregateType(
                event != null ? event.getAggregateType() : null)) {
            if (billingNotificationService != null) {
                billingNotificationService.handleOutboxEvent(event);
            } else {
                log.info("billing_notification_skip_disabled eventId={}", event != null ? event.getId() : null);
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
            // A queued bulk move: perform the move itself here, then let the resulting
            // SESSION_RESCHEDULED event drive sync and notifications through the normal
            // path. Failures (target slot taken) stay isolated to this one session.
            if ("SESSION_MOVE_REQUESTED".equals(event.getEventType())) {
                applyQueuedSessionMove(event);
                return;
            }
            UUID sessionSyncJobId = createSessionSyncJobIfNeeded(event);
            if ("REGISTRATION_CONFIRMED".equals(event.getEventType())) {
                ensureSessionConfirmationNotificationReady(event, sessionSyncJobId);
            } else {
                // A move changes two calendar events: the aggregate id covers the
                // target (attendee added), so the source (attendee removed) needs its
                // own sync job or it keeps showing a guest who left.
                if ("REGISTRATION_MOVED".equals(event.getEventType())) {
                    UUID sourceJobId = createSourceSessionSyncJobForMove(event);
                    if (sessionSyncWorker != null && sourceJobId != null) {
                        sessionSyncWorker.processJob(sourceJobId);
                    }
                }
                if (sessionSyncWorker != null && sessionSyncJobId != null) {
                    sessionSyncWorker.processJob(sessionSyncJobId);
                }
                if (sessionNotificationService != null) {
                    sessionNotificationService.handleSessionOutboxEvent(event);
                }
            }
            return;
        }

        if (!isBookingSyncCandidate(event)) {
            log.info("outbox.dispatch id={} type={} aggregateType={} aggregateId={}",
                    event.getId(), event.getEventType(),
                    event.getAggregateType(), event.getAggregateId());
            return;
        }

        // Load booking + event type once — reused for both the notification gate and
        // the sync-job path so we avoid a second round-trip for each.
        Booking booking = bookingRepository.findAnyById(event.getAggregateId()).orElse(null);
        EventType eventType = booking != null ? bookingEventTypeResolver.requireForBooking(booking) : null;
        EventKind kind = eventType != null ? eventType.getKind() : null;

        // Establish ownership BEFORE notifying. The notification decides whether to attach an ICS by
        // asking booking_ownership "did we write this event onto the recipient's own calendar?" —
        // and if the row does not exist yet, the answer is a silent "no" and the projection owner
        // is sent an iTIP REQUEST on top of the calendar entry the sync job is about to create.
        // Two identical events, which is exactly what Outlook hosts were seeing.
        //
        // Google Meet was escaping this by accident: it defers its notification to
        // BOOKING_CONFIRMED_READY, which the sync worker only emits after the projection exists.
        // Zoom, Teams, Custom URL and None do not defer, so they all notified against an ownership
        // row that had not been written yet.
        //
        // ensureOwnership is idempotent, so resolving it up front costs nothing — resolveSchedulingConnection
        // below calls it again and gets the same row.
        String desiredAction = mapDesiredAction(event.getEventType());
        if (desiredAction != null && booking != null && eventType != null) {
            bookingOwnershipService.ensureOwnership(booking, eventType);
        }

        boolean deferNotification = shouldDeferNotificationUntilProjection(event, booking, eventType);
        if (bookingNotificationService != null && !deferNotification) {
            bookingNotificationService.handleOutboxEvent(event);
        }

        if (desiredAction != null) {
            UUID partitionKey = event.getPartitionKey();
            if (partitionKey == null) {
                partitionKey = booking != null ? booking.getHostId() : null;
            }
            SchedulingResolution resolution = resolveSchedulingConnection(event.getAggregateId(), partitionKey);
            if (resolution == null && partitionKey != null) {
                log.warn("outbox.sync_job_skipped_missing_projection_ownership bookingId={} hostId={} action={}",
                        event.getAggregateId(), partitionKey, desiredAction);
                meterRegistry.counter("sync_jobs_skipped_missing_ownership_total").increment();
                return;
            }
            io.bunnycal.booking.ownership.BookingOwnership ownership = booking != null && eventType != null
                    ? bookingOwnershipService.ensureOwnership(booking, eventType)
                    : null;
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

        log.info("outbox.dispatch id={} type={} aggregateType={} aggregateId={}",
                event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId());
    }

    private static boolean isBookingSyncCandidate(OutboxEvent event) {
        return event != null
                && BOOKING_AGGREGATE.equals(event.getAggregateType())
                && event.getAggregateId() != null;
    }

    /**
     * The event type stores {@code DEFAULT} — a pointer — so it must be resolved against the writer
     * before being compared to a real provider. Comparing the raw stored value would answer "not
     * Meet" for every default-bound event type, the confirmation would go out before the sync job
     * had created the Meet link, and the guest would receive a booking email with no way to join.
     */
    private boolean shouldDeferNotificationUntilProjection(OutboxEvent event,
                                                           @Nullable Booking booking,
                                                           @Nullable EventType eventType) {
        if (event == null || !"BOOKING_CONFIRMED".equals(event.getEventType())) {
            return false;
        }
        if (eventType == null || booking == null) {
            return false;
        }
        ConferencingProviderType resolved = conferencingResolver.resolve(booking.getHostId(), eventType);
        return resolved == ConferencingProviderType.GOOGLE_MEET
                || resolved == ConferencingProviderType.MICROSOFT_TEAMS;
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

    /**
     * Executes one queued session move from a bulk "move pinned sessions" request.
     *
     * <p>A conflict at the target is an expected outcome, not a delivery failure: the
     * host resolves it from the pinned list. Rethrowing would send the event through the
     * retry/DLQ machinery for something no retry can fix, so it is logged and swallowed.
     */
    private void applyQueuedSessionMove(OutboxEvent event) {
        UUID sessionId = event.getAggregateId();
        UUID hostId = event.getPartitionKey();
        Instant target = extractPayloadInstant(event, "targetStartTime");
        if (sessionId == null || hostId == null || target == null) {
            log.warn("outbox.session_move_skipped_incomplete_payload eventId={}", event.getId());
            return;
        }
        try {
            sessionServiceProvider.getObject().rescheduleSession(sessionId, hostId, target);
            log.info("outbox.session_move_applied sessionId={} target={}", sessionId, target);
        } catch (CustomException ex) {
            log.warn("outbox.session_move_failed sessionId={} target={} reason={}",
                    sessionId, target, ex.getMessage());
        }
    }

    @Nullable
    private Instant extractPayloadInstant(OutboxEvent event, String key) {
        try {
            String raw = event.getPayload();
            if (raw == null || raw.isBlank()) return null;
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            com.fasterxml.jackson.databind.JsonNode data = root.has("payload")
                    ? root.get("payload") : root.get("data");
            if (data == null || !data.hasNonNull(key)) return null;
            return Instant.parse(data.get(key).asText());
        } catch (Exception ex) {
            log.warn("outbox.payload_instant_parse_failed key={} eventId={}", key,
                    event != null ? event.getId() : null);
            return null;
        }
    }

    /**
     * Enqueues a sync job for the session a moved registration <em>left</em>.
     *
     * <p>{@link #createSessionSyncJobIfNeeded} keys off the event's aggregate id, which
     * for a move is the destination. The source session also changed — it lost an
     * attendee — so without this its external calendar event keeps listing a guest who
     * is no longer coming.
     */
    @Nullable
    private UUID createSourceSessionSyncJobForMove(OutboxEvent event) {
        UUID sourceSessionId = extractPayloadUuid(event, "sourceSessionId");
        if (sourceSessionId == null) {
            return null;
        }
        UUID hostId = event.getPartitionKey();
        SessionSyncResolution resolution = resolveSessionSyncResolution(sourceSessionId);
        requiresNewTx.executeWithoutResult(status ->
                calendarSyncJobRepository.upsertPendingJob(
                        UUID.randomUUID(),
                        "SESSION",
                        sourceSessionId,
                        resolution.provider(),
                        "UPDATE",
                        null,
                        hostId,
                        null,
                        resolution.sessionSequence()
                )
        );
        log.info("outbox.session_sync_job_created sessionId={} action=UPDATE reason=move_source",
                sourceSessionId);
        return calendarSyncJobRepository.findByInternalRefTypeAndInternalRefIdAndProvider(
                io.bunnycal.sync.state.InternalRefType.SESSION,
                sourceSessionId,
                resolution.provider())
                .map(job -> job.getId())
                .orElse(null);
    }

    @Nullable
    private UUID extractPayloadUuid(OutboxEvent event, String key) {
        try {
            String raw = event.getPayload();
            if (raw == null || raw.isBlank()) return null;
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            com.fasterxml.jackson.databind.JsonNode data = root.has("payload")
                    ? root.get("payload") : root.get("data");
            if (data == null || !data.hasNonNull(key)) return null;
            return UUID.fromString(data.get(key).asText());
        } catch (Exception ex) {
            log.warn("outbox.payload_uuid_parse_failed key={} eventId={}", key,
                    event != null ? event.getId() : null);
            return null;
        }
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
        // The session is written to its host's global write-back calendar, so that connection's
        // provider is the one the sync job routes to.
        var projection = projectionResolver.resolveForUser(session.getHostId());
        String provider = projection == null || projection.provider() == null
                ? "DEFERRED"
                : projection.provider().name().toLowerCase(java.util.Locale.ROOT);
        return new SessionSyncResolution(provider, session.getCalendarSequence());
    }

    private record SessionSyncResolution(String provider, long sessionSequence) {}

    private static String mapSessionDesiredAction(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "REGISTRATION_CONFIRMED" -> "UPDATE";
            case "REGISTRATION_CANCELLED" -> "UPDATE";
            case "REGISTRATION_MOVED" -> "UPDATE";
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
