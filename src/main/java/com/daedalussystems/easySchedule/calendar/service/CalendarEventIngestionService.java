package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarEvent;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.calendar.repository.CalendarEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarEventIngestionService {
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventRepository eventRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;

    public CalendarEventIngestionService(CalendarConnectionRepository connectionRepository,
                                         CalendarEventRepository eventRepository,
                                         SlotCacheVersionService slotCacheVersionService,
                                         CalendarConnectionWriteService connectionWriteService) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
    }

    @Transactional
    public void upsertEvents(UUID connectionId, List<IncomingCalendarEvent> incomingEvents) {
        CalendarConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar connection not found"));

        boolean changed = false;
        for (IncomingCalendarEvent incoming : incomingEvents) {
            validate(incoming);
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
            changed = true;
        }

        if (changed) {
            slotCacheVersionService.incrementVersion(connection.getUserId());
        }

        connectionWriteService.updateLastSyncedAt(connection.getId(), Instant.now(), "event_ingestion_upsert");
    }

    public record IncomingCalendarEvent(String externalEventId,
                                        Instant startsAt,
                                        Instant endsAt,
                                        boolean cancelled) {
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
}
