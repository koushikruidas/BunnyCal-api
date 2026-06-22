package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
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
    private final CalendarSyncJobRepository syncJobRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final ProviderEventProjectionService providerEventProjectionService;
    private final SyncInvariantMonitor invariantMonitor;

    public CalendarEventIngestionService(CalendarConnectionRepository connectionRepository,
                                         CalendarEventRepository eventRepository,
                                         CalendarSyncJobRepository syncJobRepository,
                                         SlotCacheVersionService slotCacheVersionService,
                                         CalendarConnectionWriteService connectionWriteService,
                                         ProviderEventProjectionService providerEventProjectionService,
                                         SyncInvariantMonitor invariantMonitor) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
        this.syncJobRepository = syncJobRepository;
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
                log.info("provider_event_filter_decision provider={} connectionId={} externalEventId={} startsAt={} endsAt={} cancelled={} apply={}",
                        connection.getProvider().name(), connectionId, incoming.externalEventId(), incoming.startsAt(), incoming.endsAt(), incoming.cancelled(), apply);
                if (!apply) {
                    continue;
                }
                CalendarEvent event = eventRepository
                        .findByConnectionIdAndProviderAndExternalEventId(connectionId,
                                connection.getProvider().name(),
                                incoming.externalEventId())
                        .orElseGet(CalendarEvent::new);
                Instant previousStartsAt = event.getStartsAt();
                Instant previousEndsAt = event.getEndsAt();
                boolean previousCancelled = event.isCancelled();
                boolean previousDeleted = event.isDeleted();
                String previousTitle = event.getTitle();
                String previousLocation = event.getLocation();
                String previousOrganizerEmail = event.getOrganizerEmail();
                boolean existed = event.getId() != null;

                event.setUserId(connection.getUserId());
                event.setConnectionId(connectionId);
                event.setProvider(connection.getProvider().name());
                event.setExternalEventId(incoming.externalEventId());
                event.setStartsAt(incoming.startsAt());
                event.setEndsAt(incoming.endsAt());
                event.setCancelled(incoming.cancelled());
                event.setDeleted(incoming.deleted());
                event.setTitle(incoming.title());
                event.setLocation(incoming.location());
                event.setOrganizerEmail(incoming.organizerEmail());
                if (incoming.externalCalendarId() != null && !incoming.externalCalendarId().isBlank()) {
                    // Only stamp when the sync layer carries a real provider calendar id.
                    // Webhook ingestion and legacy single-calendar paths leave this null;
                    // keep the existing value so we never null-out a real attribution.
                    event.setExternalCalendarId(incoming.externalCalendarId());
                }
                event.setBlocksAvailability(!isSessionProjection(connection.getProvider().name(), incoming.externalEventId()));
                eventRepository.save(event);
                log.info("calendar_event_ingestion_upsert connectionId={} externalEventId={} startsAt={} endsAt={} cancelled={} source={}",
                        connectionId, incoming.externalEventId(), incoming.startsAt(), incoming.endsAt(), incoming.cancelled(),
                        sourceAttribution == null ? "unknown" : sourceAttribution.name());
                if (existed && (timeWindowChanged(previousStartsAt, previousEndsAt, incoming.startsAt(), incoming.endsAt())
                        || previousDeleted != incoming.deleted()
                        || previousCancelled != incoming.cancelled()
                        || !Objects.equals(previousTitle, incoming.title())
                        || !Objects.equals(previousLocation, incoming.location())
                        || !Objects.equals(previousOrganizerEmail, incoming.organizerEmail()))) {
                    log.info("external_event_updated connectionId={} provider={} externalEventId={} syncMode={} startsAtOld={} endsAtOld={} startsAtNew={} endsAtNew={} cancelledOld={} cancelledNew={} titleOld={} titleNew={} locationOld={} locationNew={} organizerOld={} organizerNew={}",
                            connectionId,
                            connection.getProvider().name().toLowerCase(),
                            incoming.externalEventId(),
                            sourceAttribution == null ? "unknown" : sourceAttribution.name(),
                            previousStartsAt, previousEndsAt, incoming.startsAt(), incoming.endsAt(),
                            previousCancelled, incoming.cancelled(),
                            previousTitle, incoming.title(),
                            previousLocation, incoming.location(),
                            previousOrganizerEmail, incoming.organizerEmail());
                    if (!previousCancelled && previousStartsAt != null && previousEndsAt != null) {
                        log.info("busy_interval_removed connectionId={} provider={} externalEventId={} start={} end={} reason={}",
                                connectionId, connection.getProvider().name().toLowerCase(), incoming.externalEventId(),
                                previousStartsAt, previousEndsAt,
                                incoming.deleted() ? "external_event_deleted" : "external_event_updated");
                    }
                    if (!incoming.cancelled() && !incoming.deleted()) {
                        log.info("busy_interval_added connectionId={} provider={} externalEventId={} start={} end={} reason=external_event_updated",
                                connectionId, connection.getProvider().name().toLowerCase(), incoming.externalEventId(),
                                incoming.startsAt(), incoming.endsAt());
                    }
                }
                if (incoming.deleted()) {
                    log.info("external_event_deleted connectionId={} provider={} externalEventId={} syncMode={} projectionStatus=TOMBSTONED_HARD",
                            connectionId,
                            connection.getProvider().name().toLowerCase(),
                            incoming.externalEventId(),
                        sourceAttribution == null ? "unknown" : sourceAttribution.name());
                }
                invariantMonitor.assertState(
                        sourceAttribution == null ? "provider_ingestion_acceptance" : sourceAttribution.name().toLowerCase() + "_ingestion_acceptance",
                        (incoming.cancelled() || incoming.deleted()) ? BookingState.CANCELLED : BookingState.CONFIRMED,
                        SyncJobStatus.PROCESSING,
                        (incoming.cancelled() || incoming.deleted())
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
                log.info("projection_recomputed connectionId={} provider={} externalEventId={} syncMode={}",
                        connectionId,
                        connection.getProvider().name().toLowerCase(),
                        incoming.externalEventId(),
                        sourceAttribution == null ? "unknown" : sourceAttribution.name());
            } finally {
                MDC.remove("externalEventId");
            }
        }

        if (changed) {
            slotCacheVersionService.bumpVersionAfterCommit(connection.getUserId());
        }

        connectionWriteService.updateLastSyncedAt(connection.getId(), Instant.now(), "event_ingestion_upsert");
    }

    private boolean isSessionProjection(String provider, String externalEventId) {
        if (provider == null || provider.isBlank() || externalEventId == null || externalEventId.isBlank()) {
            return false;
        }
        return syncJobRepository.findLatestSessionSyncByProviderAndExternalEventId(provider, externalEventId).isPresent();
    }

    public record IncomingCalendarEvent(String externalEventId,
                                        Instant startsAt,
                                        Instant endsAt,
                                        boolean cancelled,
                                        boolean deleted,
                                        Long providerSequence,
                                        Instant providerUpdatedAt,
                                        String providerEtag,
                                        String payloadHash,
                                        String externalCalendarId,
                                        String title,
                                        String location,
                                        String organizerEmail) {
        public IncomingCalendarEvent(String externalEventId,
                                     Instant startsAt,
                                     Instant endsAt,
                                     boolean cancelled) {
            this(externalEventId, startsAt, endsAt, cancelled, false, null, null, null, null, null, null, null, null);
        }

        public IncomingCalendarEvent(String externalEventId,
                                     Instant startsAt,
                                     Instant endsAt,
                                     boolean cancelled,
                                     Long providerSequence,
                                     Instant providerUpdatedAt,
                                     String providerEtag,
                                     String payloadHash) {
            this(externalEventId, startsAt, endsAt, cancelled, false, providerSequence, providerUpdatedAt, providerEtag, payloadHash, null, null, null, null);
        }

        public IncomingCalendarEvent(String externalEventId,
                                     Instant startsAt,
                                     Instant endsAt,
                                     boolean cancelled,
                                     boolean deleted,
                                     Long providerSequence,
                                     Instant providerUpdatedAt,
                                     String providerEtag,
                                     String payloadHash) {
            this(externalEventId, startsAt, endsAt, cancelled, deleted, providerSequence, providerUpdatedAt, providerEtag, payloadHash, null, null, null, null);
        }

        public IncomingCalendarEvent withExternalCalendarId(String externalCalendarId) {
            return new IncomingCalendarEvent(externalEventId, startsAt, endsAt, cancelled,
                    deleted, providerSequence, providerUpdatedAt, providerEtag, payloadHash, externalCalendarId, title, location, organizerEmail);
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

    private static boolean timeWindowChanged(Instant oldStart, Instant oldEnd, Instant newStart, Instant newEnd) {
        return !Objects.equals(oldStart, newStart) || !Objects.equals(oldEnd, newEnd);
    }
}
