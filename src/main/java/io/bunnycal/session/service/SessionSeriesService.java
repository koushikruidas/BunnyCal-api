package io.bunnycal.session.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.dto.PinnedSessionResponse;
import io.bunnycal.session.dto.SeriesCancelPreviewResponse;
import io.bunnycal.session.dto.SeriesOperationResponse;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Host operations that span a whole recurring series rather than one occurrence:
 * listing sessions that pinned away from their rule, moving them in bulk, and
 * cancelling the remaining series.
 *
 * <p>Both bulk operations are queued through the outbox rather than executed inline. A
 * weekly class with a long horizon can have dozens or hundreds of booked sessions, and
 * doing that work in the request would mean hundreds of calendar writes and notification
 * batches inside one HTTP call. Queuing also buys retry, backoff, and a DLQ for free, and
 * lets a single failure (one target slot occupied) surface without aborting the rest.
 */
@Service
public class SessionSeriesService {

    private static final Logger log = LoggerFactory.getLogger(SessionSeriesService.class);

    private final EventSessionRepository sessionRepository;
    private final EventTypeRepository eventTypeRepository;
    private final OutboxPublisher outboxPublisher;
    private final SessionService sessionService;
    private final TimeSource timeSource;

    public SessionSeriesService(EventSessionRepository sessionRepository,
                                EventTypeRepository eventTypeRepository,
                                OutboxPublisher outboxPublisher,
                                SessionService sessionService,
                                TimeSource timeSource) {
        this.sessionRepository = sessionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.outboxPublisher = outboxPublisher;
        this.sessionService = sessionService;
        this.timeSource = timeSource;
    }

    /**
     * Booked future sessions that no longer follow their rule, so the host can see
     * exactly what stayed behind after a schedule change.
     */
    @Transactional(readOnly = true)
    public List<PinnedSessionResponse> listPinnedSessions(UUID requesterId, UUID eventTypeId) {
        requireOwnedEventType(requesterId, eventTypeId);
        return sessionRepository.findPinnedFutureSessions(eventTypeId, timeSource.now()).stream()
                .map(s -> new PinnedSessionResponse(
                        s.getId(),
                        s.getStartTime(),
                        s.getEndTime(),
                        s.getScheduledOccurrenceStart(),
                        s.getConfirmedCount(),
                        s.getCapacity(),
                        s.getDetachedReason() != null ? s.getDetachedReason().name() : null,
                        s.getDetachedAt()))
                .toList();
    }

    /**
     * Booked future sessions per reservation window, for windows that have any.
     *
     * <p>Counts sessions still following their rule — the ones an edit to that window would
     * pin. Distinct from {@link #listPinnedSessions}, which reports sessions that have
     * <em>already</em> detached.
     */
    public Map<UUID, Long> countBookedSessionsByWindow(UUID requesterId, UUID eventTypeId) {
        requireOwnedEventType(requesterId, eventTypeId);
        return sessionRepository.countBookedFutureSessionsByWindow(eventTypeId, timeSource.now())
                .stream()
                .collect(Collectors.toMap(
                        EventSessionRepository.WindowBookedCountRow::getWindowId,
                        EventSessionRepository.WindowBookedCountRow::getBookedCount));
    }

    /**
     * Queues a move of every pinned session by the same offset the rule moved.
     *
     * <p>Each session is queued individually so one conflict does not abort the batch;
     * the host resolves failures from the pinned list afterwards.
     */
    @Transactional
    public SeriesOperationResponse movePinnedSessions(UUID requesterId,
                                                       UUID eventTypeId,
                                                       List<UUID> sessionIds,
                                                       Instant targetStartTime) {
        requireOwnedEventType(requesterId, eventTypeId);
        List<EventSession> pinned = sessionRepository.findPinnedFutureSessions(eventTypeId, timeSource.now());
        List<EventSession> selected = sessionIds == null || sessionIds.isEmpty()
                ? pinned
                : pinned.stream().filter(s -> sessionIds.contains(s.getId())).toList();

        if (selected.isEmpty()) {
            return new SeriesOperationResponse(0, "No pinned sessions to move.");
        }
        // A single explicit target only makes sense for a single session; moving many
        // to one instant would collapse them onto the same slot.
        if (targetStartTime != null && selected.size() > 1) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "An explicit start time can only be given when moving a single session.");
        }

        for (EventSession session : selected) {
            outboxPublisher.publish("Session", session.getId(), requesterId,
                    new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "SESSION_MOVE_REQUESTED", 1,
                            new SessionMoveRequestedEvent(
                                    session.getId(), requesterId, eventTypeId,
                                    targetStartTime != null
                                            ? targetStartTime
                                            : session.getScheduledOccurrenceStart())));
        }
        log.info("session_bulk_move_queued eventTypeId={} hostId={} count={}",
                eventTypeId, requesterId, selected.size());
        return new SeriesOperationResponse(selected.size(),
                selected.size() + " session(s) queued to move.");
    }

    /**
     * What cancelling the rest of the series would affect. Shown before the destructive
     * call so the host confirms against real numbers rather than a guess.
     */
    @Transactional(readOnly = true)
    public SeriesCancelPreviewResponse previewCancelSeries(UUID requesterId,
                                                            UUID eventTypeId,
                                                            Instant from) {
        requireOwnedEventType(requesterId, eventTypeId);
        Instant effectiveFrom = from != null ? from : timeSource.now();
        List<EventSession> affected = sessionRepository.findActiveSessionsFrom(eventTypeId, effectiveFrom);
        int guests = affected.stream().mapToInt(EventSession::getConfirmedCount).sum();
        return new SeriesCancelPreviewResponse(affected.size(), guests, effectiveFrom);
    }

    /**
     * Cancels every remaining session in the series.
     *
     * <p>Sessions are soft-cancelled — status only — so bookings, notification history,
     * and any future refund trail stay intact. Past and already-completed sessions are
     * untouched: the series stops here, it is not erased.
     */
    @Transactional
    public SeriesOperationResponse cancelSeries(UUID requesterId, UUID eventTypeId, Instant from) {
        requireOwnedEventType(requesterId, eventTypeId);
        Instant effectiveFrom = from != null ? from : timeSource.now();
        List<EventSession> affected = sessionRepository.findActiveSessionsFrom(eventTypeId, effectiveFrom);
        if (affected.isEmpty()) {
            return new SeriesOperationResponse(0, "No upcoming sessions to cancel.");
        }

        int cancelled = 0;
        for (EventSession session : affected) {
            // Delegate to the single-session path rather than reimplementing it. It
            // already does the CAS, the bulk registration cancel, and — critically —
            // publishes the SESSION_CANCELLED payload with the full attendee list that
            // guest notifications and iTIP CANCEL depend on. A leaner series-specific
            // event would leave guests uninformed.
            sessionService.cancelSession(session.getId(), requesterId);
            cancelled++;
        }
        log.info("session_series_cancelled eventTypeId={} hostId={} count={}",
                eventTypeId, requesterId, cancelled);
        return new SeriesOperationResponse(cancelled, cancelled + " session(s) cancelled.");
    }

    private EventType requireOwnedEventType(UUID requesterId, UUID eventTypeId) {
        return eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
    }

    /** Queued request to move one pinned session; consumed by the outbox dispatcher. */
    public record SessionMoveRequestedEvent(UUID sessionId, UUID hostId, UUID eventTypeId,
                                             Instant targetStartTime) {}
}
