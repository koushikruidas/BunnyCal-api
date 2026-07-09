package io.bunnycal.calendar.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.BookingSubmissionFormatter;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.ownership.BookingOwnershipRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.DeleteEventRequest;
import io.bunnycal.calendar.provider.GoogleCalendarProvider;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.bunnycal.embed.public_.BookingQuestionAnswerRepository;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class GoogleCalendarProviderClient implements CalendarProviderClient {

    @Override
    public CalendarProviderType providerType() {
        return CalendarProviderType.GOOGLE;
    }

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarProviderClient.class);
    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository calendarRepository;
    private final GoogleCalendarProvider googleCalendarProvider;
    private final BookingOwnershipRepository bookingOwnershipRepository;
    private final BookingQuestionAnswerRepository bookingQuestionAnswerRepository;
    private final BookingSubmissionFormatter bookingSubmissionFormatter;

    @Autowired
    public GoogleCalendarProviderClient(BookingRepository bookingRepository,
                                        EventTypeRepository eventTypeRepository,
                                        UserRepository userRepository,
                                        CalendarConnectionRepository connectionRepository,
                                        CalendarConnectionCalendarRepository calendarRepository,
                                        GoogleCalendarProvider googleCalendarProvider,
                                        BookingOwnershipRepository bookingOwnershipRepository,
                                        BookingQuestionAnswerRepository bookingQuestionAnswerRepository,
                                        BookingSubmissionFormatter bookingSubmissionFormatter) {
        this.bookingRepository = bookingRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.calendarRepository = calendarRepository;
        this.googleCalendarProvider = googleCalendarProvider;
        this.bookingOwnershipRepository = bookingOwnershipRepository;
        this.bookingQuestionAnswerRepository = bookingQuestionAnswerRepository;
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
    }

    public GoogleCalendarProviderClient(BookingRepository bookingRepository,
                                        EventTypeRepository eventTypeRepository,
                                        UserRepository userRepository,
                                        CalendarConnectionRepository connectionRepository,
                                        CalendarConnectionCalendarRepository calendarRepository,
                                        GoogleCalendarProvider googleCalendarProvider,
                                        BookingOwnershipRepository bookingOwnershipRepository) {
        this(bookingRepository, eventTypeRepository, userRepository, connectionRepository, calendarRepository,
                googleCalendarProvider, bookingOwnershipRepository, null, new BookingSubmissionFormatter(new ObjectMapper()));
    }

    @Override
    public CreateEventDetails createEvent(UUID internalId,
                                          String provider,
                                          String idempotencyKey,
                                          ConferencingInstruction conferencingInstruction,
                                          @Nullable UUID schedulingConnectionId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findById(booking.getEventTypeId()).orElse(null);
        User host = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CalendarClientException(404, "host user not found"));
        CalendarConnection connection = resolveConnection(booking.getHostId(), provider, schedulingConnectionId);

        String title = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = buildBookingDescription(booking);
        String targetCalendarId = resolveTargetCalendarId(booking.getId());
        ConferencingInstruction instruction = conferencingInstruction == null
                ? ConferencingInstruction.none()
                : conferencingInstruction;
        log.info("google_calendar_event_create_request bookingId={} provider={} connectionId={} providerUserId={} targetCalendarId={} attendeeCount={} attendeeEmails={} sendUpdates=none conferencingMode={} conferencingProvider={}",
                booking.getId(), provider, connection.getId(), connection.getProviderUserId(), targetCalendarId,
                0, "[]", instruction.mode(), instruction.providerType());
        log.info("google_calendar_event_create_time bookingId={} provider={} startTimeUtc={} endTimeUtc={} hostTimezone={} source=booking_instants",
                booking.getId(), provider, booking.getStartTime(), booking.getEndTime(), host.getTimezone());

        var response = googleCalendarProvider.createEvent(new CreateEventRequest(
                connection.getId(),
                title,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                host.getEmail(),
                null,
                null,
                idempotencyKey,
                targetCalendarId,
                instruction
        ));
        String externalEventId = response.externalEventId();
        log.info("provider_authority_isolation provider=google action=create sendUpdates=none organizerAuthority=application");
        log.info("google_calendar_event_create_success bookingId={} provider={} externalEventId={}",
                booking.getId(), provider, externalEventId);
        return new CreateEventDetails(externalEventId, response.providerEventUrl(), response.conferenceUrl());
    }

    @Override
    public String updateEvent(UUID internalId,
                              String provider,
                              String externalEventId,
                              String idempotencyKey,
                              ConferencingInstruction conferencingInstruction,
                              @Nullable UUID schedulingConnectionId) {
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        EventType eventType = eventTypeRepository.findById(booking.getEventTypeId()).orElse(null);
        User host = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CalendarClientException(404, "host user not found"));
        CalendarConnection connection = resolveConnection(booking.getHostId(), provider, schedulingConnectionId);

        String title = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = buildBookingDescription(booking);
        String targetCalendarId = resolveTargetCalendarId(booking.getId());
        ConferencingInstruction instruction = conferencingInstruction == null
                ? ConferencingInstruction.none()
                : conferencingInstruction;
        log.info("google_calendar_event_update_request bookingId={} provider={} connectionId={} providerUserId={} targetCalendarId={} externalEventId={} attendeeCount={} attendeeEmails={} sendUpdates=none conferencingMode={} conferencingProvider={}",
                booking.getId(), provider, connection.getId(), connection.getProviderUserId(), targetCalendarId, externalEventId,
                0, "[]", instruction.mode(), instruction.providerType());

        String updatedExternalId = googleCalendarProvider.updateEvent(new UpdateEventRequest(
                connection.getId(),
                externalEventId,
                title,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                host.getEmail(),
                null,
                null,
                targetCalendarId,
                instruction
        )).externalEventId();
        log.info("provider_authority_isolation provider=google action=update sendUpdates=none organizerAuthority=application");
        log.info("google_calendar_event_update_success bookingId={} provider={} externalEventId={}",
                booking.getId(), provider, updatedExternalId);
        return updatedExternalId;
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
        googleCalendarProvider.deleteEvent(new DeleteEventRequest(connection.getId(), externalEventId));
    }

    @Override
    public boolean eventExists(UUID internalId, String provider, String externalEventId,
                               @Nullable UUID schedulingConnectionId) {
        if (externalEventId == null || externalEventId.isBlank()) {
            return false;
        }
        Booking booking = bookingRepository.findAnyById(internalId)
                .orElseThrow(() -> new CalendarClientException(404, "booking not found"));
        CalendarConnection connection = schedulingConnectionId != null
                ? connectionRepository.findById(schedulingConnectionId)
                        .orElseGet(() -> resolveAnyConnection(booking.getHostId(), provider))
                : resolveAnyConnection(booking.getHostId(), provider);
        return googleCalendarProvider.eventExists(new DeleteEventRequest(connection.getId(), externalEventId));
    }

    @Override
    public boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey,
                                @Nullable UUID schedulingConnectionId) {
        // Google-backed flow does not persist idempotency metadata on provider events.
        // Treat current existence as "match" for reconciliation purposes.
        return eventExists(internalId, provider, externalEventId, schedulingConnectionId);
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

    private String resolveTargetCalendarId(UUID bookingId) {
        String calendarId = bookingOwnershipRepository.findByBookingId(bookingId)
                .map(ownership -> ownership.getProjectionCalendarId())
                .orElse(null);
        if (calendarId == null || calendarId.isBlank()) {
            throw new CalendarClientException(400, "projection calendar ownership is missing");
        }
        return calendarId.trim();
    }

    private String buildBookingDescription(Booking booking) {
        return bookingSubmissionFormatter.buildBookingDescription(
                booking,
                bookingSubmissionFormatter.toResponses(
                        bookingQuestionAnswerRepository == null
                                ? java.util.List.of()
                                : bookingQuestionAnswerRepository.findByBookingIdAndHostId(booking.getId(), booking.getHostId())));
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
