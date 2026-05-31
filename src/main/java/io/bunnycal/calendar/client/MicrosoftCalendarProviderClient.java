package io.bunnycal.calendar.client;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.DeleteEventRequest;
import io.bunnycal.calendar.provider.MicrosoftCalendarProvider;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class MicrosoftCalendarProviderClient implements CalendarProviderClient {
    private static final Logger log = LoggerFactory.getLogger(MicrosoftCalendarProviderClient.class);

    @Override
    public CalendarProviderType providerType() {
        return CalendarProviderType.MICROSOFT;
    }

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
    public CreateEventDetails createEvent(UUID internalId, String provider, String idempotencyKey,
                                          ConferencingInstruction conferencingInstruction,
                                          @Nullable UUID schedulingConnectionId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId()).orElse(null);
        User host = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CalendarClientException(404, "host user not found"));
        CalendarConnection connection = resolveConnection(booking.getHostId(), provider, schedulingConnectionId);

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
                resolveTargetCalendarId(eventType),
                conferencingInstruction == null ? ConferencingInstruction.none() : conferencingInstruction
        ));
        log.info("provider_authority_isolation provider=microsoft action=create responseRequested=false organizerAuthority=application");
        return new CreateEventDetails(response.externalEventId(), response.providerEventUrl(), response.conferenceUrl());
    }

    @Override
    public String updateEvent(UUID internalId, String provider, String externalEventId, String idempotencyKey,
                              ConferencingInstruction conferencingInstruction,
                              @Nullable UUID schedulingConnectionId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId()).orElse(null);
        User host = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CalendarClientException(404, "host user not found"));
        CalendarConnection connection = resolveConnection(booking.getHostId(), provider, schedulingConnectionId);

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
                resolveTargetCalendarId(eventType),
                conferencingInstruction == null ? ConferencingInstruction.none() : conferencingInstruction
        )).externalEventId();
        log.info("provider_authority_isolation provider=microsoft action=update responseRequested=false organizerAuthority=application");
        return updated;
    }

    @Override
    public void deleteEvent(UUID internalId, String provider, String externalEventId,
                            @Nullable UUID schedulingConnectionId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        CalendarConnection connection = schedulingConnectionId != null
                ? connectionRepository.findById(schedulingConnectionId)
                        .orElseGet(() -> resolveAnyConnection(booking.getHostId(), provider))
                : resolveAnyConnection(booking.getHostId(), provider);
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

    private CalendarConnection resolveConnection(UUID userId, String provider,
                                                    @Nullable UUID schedulingConnectionId) {
        if (schedulingConnectionId != null) {
            return connectionRepository.findById(schedulingConnectionId)
                    .orElseThrow(() -> new CalendarClientException(404, "scheduling connection not found"));
        }
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

    private static String resolveTargetCalendarId(EventType eventType) {
        if (eventType == null || eventType.getProjectionCalendarId() == null || eventType.getProjectionCalendarId().isBlank()) {
            throw new CalendarClientException(400, "projection calendar ownership is missing");
        }
        return eventType.getProjectionCalendarId().trim();
    }

    private static String normalizeAttendeeEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
