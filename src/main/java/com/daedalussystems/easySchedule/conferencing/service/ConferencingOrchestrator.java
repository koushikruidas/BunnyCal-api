package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.conferencing.domain.ConferencingEventMapping;
import com.daedalussystems.easySchedule.conferencing.repository.ConferencingEventMappingRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConferencingOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ConferencingOrchestrator.class);

    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final ConferencingProviderRegistry conferencingProviderRegistry;
    private final ConferencingEventMappingRepository conferencingEventMappingRepository;
    private final CalendarEventMappingRepository calendarEventMappingRepository;

    public ConferencingOrchestrator(BookingRepository bookingRepository,
                                    EventTypeRepository eventTypeRepository,
                                    ConferencingProviderRegistry conferencingProviderRegistry,
                                    ConferencingEventMappingRepository conferencingEventMappingRepository,
                                    CalendarEventMappingRepository calendarEventMappingRepository) {
        this.bookingRepository = bookingRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.conferencingProviderRegistry = conferencingProviderRegistry;
        this.conferencingEventMappingRepository = conferencingEventMappingRepository;
        this.calendarEventMappingRepository = calendarEventMappingRepository;
    }

    @Transactional
    public void afterCalendarSyncSuccess(CalendarSyncJob job, SyncDesiredAction action) {
        try {
            var booking = (job.getPartitionKey() != null
                    ? bookingRepository.findById(new BookingId(job.getInternalRefId(), job.getPartitionKey()))
                    : bookingRepository.findAnyById(job.getInternalRefId()))
                    .orElse(null);
            if (booking == null) return;
            EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId()).orElse(null);
            if (eventType == null) return;
            ConferencingProviderType providerType = eventType.getConferencingProvider();
            if (providerType == null || providerType == ConferencingProviderType.NONE || providerType == ConferencingProviderType.CUSTOM_URL) {
                return;
            }
            if (providerType == ConferencingProviderType.GOOGLE_MEET) {
                return;
            }
            var provider = conferencingProviderRegistry.resolve(providerType);
            ConferencingEventMapping mapping = conferencingEventMappingRepository
                    .findByBookingIdAndProvider(booking.getId(), providerType)
                    .orElseGet(() -> {
                        ConferencingEventMapping created = new ConferencingEventMapping();
                        created.setBookingId(booking.getId());
                        created.setProvider(providerType);
                        created.setStatus("PENDING");
                        return created;
                    });

            if (action == SyncDesiredAction.DELETE) {
                if (mapping.getMeetingId() != null && !mapping.getMeetingId().isBlank()) {
                    provider.cancelMeeting(booking.getId(), booking.getHostId(), mapping.getMeetingId());
                }
                mapping.setStatus("CANCELLED");
                mapping.setLastError(null);
                conferencingEventMappingRepository.save(mapping);
                calendarEventMappingRepository.updateConferenceUrl(booking.getId(), job.getProvider(), null);
                return;
            }

            ConferencingProvider.MeetingDetails details;
            if (mapping.getMeetingId() == null || mapping.getMeetingId().isBlank() || action == SyncDesiredAction.CREATE) {
                details = provider.createMeeting(booking.getId(), booking.getHostId(), eventType.getName(), booking.getStartTime(), booking.getEndTime());
            } else {
                details = provider.updateMeeting(booking.getId(), booking.getHostId(), mapping.getMeetingId(), eventType.getName(), booking.getStartTime(), booking.getEndTime());
            }
            if (details.meetingId() != null) {
                mapping.setMeetingId(details.meetingId());
            }
            if (details.joinUrl() != null) {
                mapping.setJoinUrl(details.joinUrl());
                calendarEventMappingRepository.updateConferenceUrl(booking.getId(), job.getProvider(), details.joinUrl());
            }
            if (details.hostUrl() != null) {
                mapping.setHostUrl(details.hostUrl());
            }
            mapping.setStatus("ACTIVE");
            mapping.setLastError(null);
            conferencingEventMappingRepository.save(mapping);
        } catch (RuntimeException ex) {
            log.warn("conferencing_orchestration_failed bookingId={} action={} provider={}",
                    job.getInternalRefId(), action, job.getProvider(), ex);
        }
    }
}
