package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import io.bunnycal.sync.invariants.LineageContext;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.state.SyncJobStatus;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarEventIngestionService {
    private static final Logger log = LoggerFactory.getLogger(CalendarEventIngestionService.class);
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventRepository eventRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final ProviderEventProjectionService providerEventProjectionService;
    private final SyncInvariantMonitor invariantMonitor;

    public CalendarEventIngestionService(CalendarConnectionRepository connectionRepository,
                                         CalendarEventRepository eventRepository,
                                         SlotCacheVersionService slotCacheVersionService,
                                         CalendarConnectionWriteService connectionWriteService,
                                         ProviderEventProjectionService providerEventProjectionService,
                                         SyncInvariantMonitor invariantMonitor) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
        this.providerEventProjectionService = providerEventProjectionService;
        this.invariantMonitor = invariantMonitor;
    }

    @Transactional
    public void upsertEvents(UUID connectionId, List<IncomingCalendarEvent> incomingEvents) {
        upsertEvents(connectionId, incomingEvents, SyncSourceAttribution.PULL_SYNC);
    }

    @Transactional
    public void upsertEvents(UUID connectionId,
                             List<IncomingCalendarEvent> incomingEvents,
                             SyncSourceAttribution sourceAttribution) {
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId is required");
        }
        log.info("calendar_event_ingestion_lookup connectionId={} incomingCount={}",
                connectionId, incomingEvents == null ? 0 : incomingEvents.size());
        CalendarConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar connection not found"));

        boolean changed = false;
        for (IncomingCalendarEvent incoming : incomingEvents) {
            validate(incoming);
            MDC.put("externalEventId", incoming.externalEventId());
            try {
                boolean apply = providerEventProjectionService.shouldApplyAndAdvance(
                        connectionId,
                        connection.getProvider().name(),
                        incoming);
                log.info("microsoft_event_filter_decision connectionId={} externalEventId={} startsAt={} endsAt={} cancelled={} apply={}",
                        connectionId, incoming.externalEventId(), incoming.startsAt(), incoming.endsAt(), incoming.cancelled(), apply);
                if (!apply) {
                    continue;
                }
                CalendarEvent event = eventRepository
                        .findByConnectionIdAndProviderAndExternalEventId(connectionId,
                                connection.getProvider().name(),
                                incoming.externalEventId())
                        .orElseGet(CalendarEvent::new);

                event.setUserId(connection.getUserId());
                event.setConnectionId(connectionId);
                event.setProvider(connection.getProvider().name());
                event.setExternalEventId(incoming.externalEventId());
                event.setStartsAt(incoming.startsAt());
                event.setEndsAt(incoming.endsAt());
                event.setCancelled(incoming.cancelled());
                eventRepository.save(event);
                log.info("calendar_event_ingestion_upsert connectionId={} externalEventId={} startsAt={} endsAt={} cancelled={} source={}",
                        connectionId, incoming.externalEventId(), incoming.startsAt(), incoming.endsAt(), incoming.cancelled(),
                        sourceAttribution == null ? "unknown" : sourceAttribution.name());
                invariantMonitor.assertState(
                        sourceAttribution == null ? "provider_ingestion_acceptance" : sourceAttribution.name().toLowerCase() + "_ingestion_acceptance",
                        incoming.cancelled() ? BookingState.CANCELLED : BookingState.CONFIRMED,
                        SyncJobStatus.PROCESSING,
                        incoming.cancelled()
                                ? CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT
                                : CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION,
                        new LineageContext(
                                safeMdc("correlationId"),
                                safeMdc("causationId"),
                                safeMdc("bookingId"),
                                incoming.externalEventId(),
                                "unknown",
                                safeMdc("terminalIntentEpoch")));
                changed = true;
            } finally {
                MDC.remove("externalEventId");
            }
        }

        if (changed) {
            slotCacheVersionService.incrementVersion(connection.getUserId());
        }

        connectionWriteService.updateLastSyncedAt(connection.getId(), Instant.now(), "event_ingestion_upsert");
    }

    public record IncomingCalendarEvent(String externalEventId,
                                        Instant startsAt,
                                        Instant endsAt,
                                        boolean cancelled,
                                        Long providerSequence,
                                        Instant providerUpdatedAt,
                                        String providerEtag,
                                        String payloadHash) {
        public IncomingCalendarEvent(String externalEventId,
                                     Instant startsAt,
                                     Instant endsAt,
                                     boolean cancelled) {
            this(externalEventId, startsAt, endsAt, cancelled, null, null, null, null);
        }

        public LocalDate startDateUtc() {
            return startsAt.atZone(ZoneOffset.UTC).toLocalDate();
        }

        public LocalDate endDateUtc() {
            return endsAt.atZone(ZoneOffset.UTC).toLocalDate();
        }
    }

    private static void validate(IncomingCalendarEvent incoming) {
        Objects.requireNonNull(incoming, "incoming event is required");
        if (incoming.externalEventId() == null || incoming.externalEventId().isBlank()) {
            throw new IllegalArgumentException("externalEventId is required");
        }
        if (incoming.startsAt() == null || incoming.endsAt() == null || !incoming.startsAt().isBefore(incoming.endsAt())) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
    }

    private static String safeMdc(String key) {
        String v = MDC.get(key);
        return v == null ? "" : v;
    }
}
