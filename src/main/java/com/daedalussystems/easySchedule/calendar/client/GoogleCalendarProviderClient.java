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
import com.daedalussystems.easySchedule.calendar.provider.GoogleCalendarProvider;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionCalendarRepository;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.provider.mode", havingValue = "google", matchIfMissing = true)
public class GoogleCalendarProviderClient implements CalendarProviderClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarProviderClient.class);
    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository calendarRepository;
    private final GoogleCalendarProvider googleCalendarProvider;

    public GoogleCalendarProviderClient(BookingRepository bookingRepository,
                                        EventTypeRepository eventTypeRepository,
                                        UserRepository userRepository,
                                        CalendarConnectionRepository connectionRepository,
                                        CalendarConnectionCalendarRepository calendarRepository,
                                        GoogleCalendarProvider googleCalendarProvider) {
        this.bookingRepository = bookingRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.calendarRepository = calendarRepository;
        this.googleCalendarProvider = googleCalendarProvider;
    }

    @Override
    public CreateEventDetails createEvent(UUID internalId, String provider, String idempotencyKey) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId())
                .orElse(null);
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
        String maskedGuest = maskEmail(attendeeEmail);
        String targetCalendarId = resolveTargetCalendarId(connection.getId());
        log.info("google_calendar_event_create_request bookingId={} provider={} connectionId={} providerUserId={} targetCalendarId={} attendeeCount={} attendeeEmails={} sendUpdates=all conferenceDataVersion=1 conferenceRequested=true",
                booking.getId(), provider, connection.getId(), connection.getProviderUserId(), targetCalendarId,
                1, maskedGuest);
        log.info("google_calendar_event_create_time bookingId={} provider={} startTimeUtc={} endTimeUtc={} hostTimezone={} source=booking_instants",
                booking.getId(), provider, booking.getStartTime(), booking.getEndTime(), host.getTimezone());
        log.info("google_calendar_attendee_source bookingId={} provider={} attendeeSource=booking.guestEmail attendeeEmail={}",
                booking.getId(), provider, maskedGuest);

        var response = googleCalendarProvider.createEvent(new CreateEventRequest(
                connection.getId(),
                title,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                host.getEmail(),
                attendeeEmail,
                booking.getGuestName(),
                idempotencyKey,
                targetCalendarId
        ));
        String externalEventId = response.externalEventId();
        log.info("google_calendar_event_create_success bookingId={} provider={} externalEventId={}",
                booking.getId(), provider, externalEventId);
        return new CreateEventDetails(externalEventId, response.providerEventUrl(), response.conferenceUrl());
    }

    @Override
    public String updateEvent(UUID internalId, String provider, String externalEventId, String idempotencyKey) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId())
                .orElse(null);
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
        String maskedGuest = maskEmail(attendeeEmail);
        String targetCalendarId = resolveTargetCalendarId(connection.getId());
        log.info("google_calendar_event_update_request bookingId={} provider={} connectionId={} providerUserId={} targetCalendarId={} externalEventId={} attendeeCount={} attendeeEmails={} sendUpdates=all conferenceDataVersion=1 conferenceRequested=true",
                booking.getId(), provider, connection.getId(), connection.getProviderUserId(), targetCalendarId, externalEventId,
                1, maskedGuest);
        log.info("google_calendar_attendee_source bookingId={} provider={} attendeeSource=booking.guestEmail attendeeEmail={}",
                booking.getId(), provider, maskedGuest);

        String updatedExternalId = googleCalendarProvider.updateEvent(new UpdateEventRequest(
                connection.getId(),
                externalEventId,
                title,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                host.getEmail(),
                attendeeEmail,
                booking.getGuestName(),
                targetCalendarId
        )).externalEventId();
        log.info("google_calendar_event_update_success bookingId={} provider={} externalEventId={}",
                booking.getId(), provider, updatedExternalId);
        return updatedExternalId;
    }

    @Override
    public void deleteEvent(UUID internalId, String provider, String externalEventId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        CalendarConnection connection = resolveAnyConnection(booking.getHostId(), provider);
        googleCalendarProvider.deleteEvent(new DeleteEventRequest(connection.getId(), externalEventId));
    }

    @Override
    public boolean eventExists(UUID internalId, String provider, String externalEventId) {
        if (externalEventId == null || externalEventId.isBlank()) {
            return false;
        }
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        CalendarConnection connection = resolveAnyConnection(booking.getHostId(), provider);
        return googleCalendarProvider.eventExists(new DeleteEventRequest(connection.getId(), externalEventId));
    }

    @Override
    public boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey) {
        // Google-backed flow does not persist idempotency metadata on provider events.
        // Treat current existence as "match" for reconciliation purposes.
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

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String normalizeAttendeeEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
