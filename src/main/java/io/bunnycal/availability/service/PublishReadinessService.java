package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.EventTypeParticipantResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.time.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single authority for all Collective event type readiness evaluation and publish-state enforcement.
 *
 * <p>All readiness decisions flow through {@link #evaluate(EventType)} and all publish-state
 * changes flow through {@link #applyAndEnforce(EventType)}. No other service should contain
 * readiness logic for COLLECTIVE event types.
 *
 * <p>For non-COLLECTIVE kinds, readiness is always trivially publishable (single-host logic
 * does not require multi-participant readiness gates). The {@link #evaluate} and
 * {@link #publishReadinessResponse} methods return sensible defaults for those kinds.
 *
 * <h2>Structural vs transient failures</h2>
 * <ul>
 *   <li>Structural: INACTIVE, REVOKED, NO_CALENDAR, NO_AVAILABILITY, NO_WRITEBACK, NOT_SCHEDULABLE.
 *       These cause auto-unpublish.</li>
 *   <li>Transient: DEGRADED_CALENDAR. The event stays published; owner receives a warning
 *       (rate-limited to 1 per 24 h via {@code last_degraded_notification_at}).</li>
 * </ul>
 */
@Service
public class PublishReadinessService {

    private static final Logger log = LoggerFactory.getLogger(PublishReadinessService.class);
    private static final Duration DEGRADED_NOTIFICATION_COOLDOWN = Duration.ofHours(24);

    private final EventTypeParticipantService participantService;
    private final EventTypeRepository eventTypeRepository;
    private final OutboxPublisher outboxPublisher;
    private final TimeSource timeSource;

    public PublishReadinessService(
            EventTypeParticipantService participantService,
            EventTypeRepository eventTypeRepository,
            OutboxPublisher outboxPublisher,
            TimeSource timeSource) {
        this.participantService = participantService;
        this.eventTypeRepository = eventTypeRepository;
        this.outboxPublisher = outboxPublisher;
        this.timeSource = timeSource;
    }

    // ── Evaluation (read-only) ────────────────────────────────────────────────

    /**
     * Evaluates current readiness without making any changes to the event type.
     * Safe to call at any time; does not persist anything.
     */
    public CollectiveReadinessSummary evaluate(EventType eventType) {
        if (eventType.getKind() != EventKind.COLLECTIVE) {
            return CollectiveReadinessSummary.alwaysPublishable(eventType.isPublished());
        }
        List<UUID> participantIds = participantService.effectiveParticipantUserIds(eventType);
        List<EventTypeParticipantResponse> enriched = participantService.enrichForReadiness(
                participantIds, eventType.getUserId());

        return buildSummary(eventType.isPublished(), enriched);
    }

    /**
     * Builds a {@link PublishReadinessResponse} for API consumption.
     */
    public PublishReadinessResponse publishReadinessResponse(EventType eventType) {
        if (eventType.getKind() != EventKind.COLLECTIVE
                && eventType.getKind() != EventKind.ROUND_ROBIN) {
            return new PublishReadinessResponse(
                    eventType.isPublished(), true, false, List.of(), 0, 0, List.of());
        }
        List<UUID> participantIds = participantService.effectiveParticipantUserIds(eventType);
        List<EventTypeParticipantResponse> enriched = participantService.enrichForReadiness(
                participantIds, eventType.getUserId());

        CollectiveReadinessSummary summary = buildSummary(eventType.isPublished(), enriched);
        int readyCount = (int) enriched.stream()
                .filter(p -> p.readinessStatus() == ParticipantReadinessStatus.READY
                        || p.readinessStatus() == ParticipantReadinessStatus.DEGRADED_CALENDAR)
                .count();
        return new PublishReadinessResponse(
                eventType.isPublished(),
                summary.publishable(),
                summary.degraded(),
                summary.reasons(),
                enriched.size(),
                readyCount,
                enriched);
    }

    // ── Enforcement (write) ───────────────────────────────────────────────────

    /**
     * Evaluates readiness and enforces publish state: if published and now unpublishable,
     * auto-unpublishes and emits an audit outbox event. If degraded, emits a rate-limited
     * degraded warning.
     *
     * <p>Must be called inside an active transaction.
     *
     * @return the current summary after enforcement
     */
    @Transactional
    public CollectiveReadinessSummary applyAndEnforce(EventType eventType) {
        CollectiveReadinessSummary summary = evaluate(eventType);

        if (eventType.isPublished() && !summary.publishable() && !summary.degraded()) {
            // Structural readiness lost — auto-unpublish.
            eventType.setPublished(false);
            eventTypeRepository.save(eventType);
            emitAutoUnpublishedEvent(eventType, summary);
            log.warn("collective.auto_unpublished eventTypeId={} reasons={}",
                    eventType.getId(), summary.reasons());
        } else if (eventType.isPublished() && summary.degraded()) {
            // Transient failure — stay published, rate-limited warning.
            maybeEmitDegradedWarning(eventType, summary);
        }

        return summary;
    }

    /**
     * Checks whether any published COLLECTIVE event types involving {@code userId} as a
     * participant need enforcement, and triggers {@link #applyAndEnforce} for each.
     * Used by the calendar-status-change handler and the periodic scheduler.
     */
    @Transactional
    public void enforceForParticipant(UUID userId) {
        List<EventType> affected = eventTypeRepository
                .findPublishedCollectiveByParticipantUserId(userId);
        for (EventType et : affected) {
            applyAndEnforce(et);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CollectiveReadinessSummary buildSummary(boolean published,
                                                     List<EventTypeParticipantResponse> participants) {
        boolean anyDegraded = false;
        List<String> reasons = new ArrayList<>();

        for (EventTypeParticipantResponse p : participants) {
            ParticipantReadinessStatus status = p.readinessStatus();
            if (isStructurallyBlocking(status)) {
                reasons.add(p.readinessMessage());
            } else if (status == ParticipantReadinessStatus.DEGRADED_CALENDAR) {
                anyDegraded = true;
            }
        }

        boolean publishable = reasons.isEmpty();
        return new CollectiveReadinessSummary(published, publishable, anyDegraded && publishable, reasons, participants);
    }

    private static boolean isStructurallyBlocking(ParticipantReadinessStatus status) {
        return switch (status) {
            case INACTIVE, REVOKED, NO_AVAILABILITY, NO_CALENDAR, NO_WRITEBACK, NOT_SCHEDULABLE -> true;
            case READY, DEGRADED_CALENDAR -> false;
        };
    }

    private void emitAutoUnpublishedEvent(EventType eventType, CollectiveReadinessSummary summary) {
        String reason = summary.reasons().isEmpty() ? "Structural readiness lost." : summary.reasons().get(0);
        List<EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot> snapshots =
                summary.participantStatuses().stream()
                        .map(p -> new EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot(
                                p.userId(),
                                p.userName(),
                                p.readinessStatus().name(),
                                p.readinessMessage()))
                        .toList();
        EventTypeLifecycleOutboxPayload payload = new EventTypeLifecycleOutboxPayload(
                eventType.getId(),
                eventType.getName(),
                eventType.getUserId(),
                reason,
                snapshots,
                timeSource.now());
        outboxPublisher.publish(
                EventTypeLifecycleOutboxPayload.AGGREGATE_TYPE,
                eventType.getId(),
                new OutboxPayloadEnvelope(
                        UUID.randomUUID().toString(),
                        EventTypeLifecycleOutboxPayload.EVENT_AUTO_UNPUBLISHED,
                        1,
                        payload));
    }

    private void maybeEmitDegradedWarning(EventType eventType, CollectiveReadinessSummary summary) {
        Instant now = timeSource.now();
        Instant lastSent = eventType.getLastDegradedNotificationAt();
        if (lastSent != null && Duration.between(lastSent, now).compareTo(DEGRADED_NOTIFICATION_COOLDOWN) < 0) {
            return; // within cooldown — skip
        }
        eventType.setLastDegradedNotificationAt(now);
        eventTypeRepository.save(eventType);

        List<EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot> snapshots =
                summary.participantStatuses().stream()
                        .filter(p -> p.readinessStatus() == ParticipantReadinessStatus.DEGRADED_CALENDAR)
                        .map(p -> new EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot(
                                p.userId(),
                                p.userName(),
                                p.readinessStatus().name(),
                                p.readinessMessage()))
                        .toList();
        EventTypeLifecycleOutboxPayload payload = new EventTypeLifecycleOutboxPayload(
                eventType.getId(),
                eventType.getName(),
                eventType.getUserId(),
                "One or more participant calendars are temporarily unavailable.",
                snapshots,
                now);
        outboxPublisher.publish(
                EventTypeLifecycleOutboxPayload.AGGREGATE_TYPE,
                eventType.getId(),
                new OutboxPayloadEnvelope(
                        UUID.randomUUID().toString(),
                        EventTypeLifecycleOutboxPayload.EVENT_READINESS_DEGRADED,
                        1,
                        payload));
        log.info("collective.readiness_degraded_notification_sent eventTypeId={}", eventType.getId());
    }

    // ── Summary record ────────────────────────────────────────────────────────

    public record CollectiveReadinessSummary(
            boolean published,
            boolean publishable,
            boolean degraded,
            List<String> reasons,
            List<EventTypeParticipantResponse> participantStatuses
    ) {
        static CollectiveReadinessSummary alwaysPublishable(boolean published) {
            return new CollectiveReadinessSummary(published, true, false, List.of(), List.of());
        }
    }
}
