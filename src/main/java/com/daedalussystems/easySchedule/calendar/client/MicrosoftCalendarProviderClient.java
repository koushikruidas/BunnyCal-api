package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.DeleteEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.MicrosoftCalendarProvider;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionCalendarRepository;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.provider.mode", havingValue = "microsoft")
public class MicrosoftCalendarProviderClient implements CalendarProviderClient {
    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository calendarRepository;
    private final MicrosoftCalendarProvider microsoftCalendarProvider;

    public MicrosoftCalendarProviderClient(BookingRepository bookingRepository,
                                           EventTypeRepository eventTypeRepository,
                                           UserRepository userRepository,
                                           CalendarConnectionRepository connectionRepository,
                                           CalendarConnectionCalendarRepository calendarRepository,
                                           MicrosoftCalendarProvider microsoftCalendarProvider) {
        this.bookingRepository = bookingRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.calendarRepository = calendarRepository;
        this.microsoftCalendarProvider = microsoftCalendarProvider;
    }

    @Override
    public CreateEventDetails createEvent(UUID internalId, String provider, String idempotencyKey, ConferencingInstruction conferencingInstruction) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId()).orElse(null);
        User host = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CalendarClientException(404, "host user not found"));
        CalendarConnection connection = resolveActiveConnection(booking.getHostId(), provider);

        String title = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = "bookingId=" + booking.getId();
        String attendeeEmail = normalizeAttendeeEmail(booking.getGuestEmail());
        if (attendeeEmail == null) {
            throw new CalendarClientException(400, "guest attendee email is required");
        }
        var response = microsoftCalendarProvider.createEvent(new CreateEventRequest(
                connection.getId(),
                title,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                host.getEmail(),
                attendeeEmail,
                booking.getGuestName(),
                idempotencyKey,
                resolveTargetCalendarId(connection.getId()),
                conferencingInstruction == null ? ConferencingInstruction.none() : conferencingInstruction
        ));
        return new CreateEventDetails(response.externalEventId(), response.providerEventUrl(), response.conferenceUrl());
    }

    @Override
    public String updateEvent(UUID internalId, String provider, String externalEventId, String idempotencyKey, ConferencingInstruction conferencingInstruction) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId()).orElse(null);
        User host = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CalendarClientException(404, "host user not found"));
        CalendarConnection connection = resolveActiveConnection(booking.getHostId(), provider);

        String title = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = "bookingId=" + booking.getId();
        String attendeeEmail = normalizeAttendeeEmail(booking.getGuestEmail());
        if (attendeeEmail == null) {
            throw new CalendarClientException(400, "guest attendee email is required");
        }
        String updated = microsoftCalendarProvider.updateEvent(new UpdateEventRequest(
                connection.getId(),
                externalEventId,
                title,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                host.getEmail(),
                attendeeEmail,
                booking.getGuestName(),
                resolveTargetCalendarId(connection.getId()),
                conferencingInstruction == null ? ConferencingInstruction.none() : conferencingInstruction
        )).externalEventId();
        return updated;
    }

    @Override
    public void deleteEvent(UUID internalId, String provider, String externalEventId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        CalendarConnection connection = resolveAnyConnection(booking.getHostId(), provider);
        microsoftCalendarProvider.deleteEvent(new DeleteEventRequest(connection.getId(), externalEventId));
    }

    @Override
    public boolean eventExists(UUID internalId, String provider, String externalEventId) {
        if (externalEventId == null || externalEventId.isBlank()) {
            return false;
        }
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        CalendarConnection connection = resolveAnyConnection(booking.getHostId(), provider);
        return microsoftCalendarProvider.eventExists(new DeleteEventRequest(connection.getId(), externalEventId));
    }

    @Override
    public boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey) {
        return eventExists(internalId, provider, externalEventId);
    }

    private CalendarConnection resolveActiveConnection(UUID userId, String provider) {
        CalendarProviderType providerType = providerType(provider);
        return connectionRepository.findByUserIdAndProviderAndStatus(userId, providerType, CalendarConnectionStatus.ACTIVE)
                .orElseThrow(() -> new CalendarClientException(404, "active calendar connection not found"));
    }

    private CalendarConnection resolveAnyConnection(UUID userId, String provider) {
        CalendarProviderType providerType = providerType(provider);
        return connectionRepository.findByUserIdAndProvider(userId, providerType)
                .orElseThrow(() -> new CalendarClientException(404, "calendar connection not found"));
    }

    private static CalendarProviderType providerType(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new CalendarClientException(400, "provider is required");
        }
        try {
            return CalendarProviderType.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CalendarClientException(400, "unsupported provider: " + provider);
        }
    }

    private String resolveTargetCalendarId(UUID connectionId) {
        return calendarRepository.findByConnectionIdAndSelectedTrue(connectionId)
                .map(c -> c.getExternalCalendarId())
                .filter(v -> v != null && !v.isBlank())
                .orElse("primary");
    }

    private static String normalizeAttendeeEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
