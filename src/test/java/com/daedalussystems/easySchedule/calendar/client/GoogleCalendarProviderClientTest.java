package com.daedalussystems.easySchedule.calendar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.daedalussystems.easySchedule.calendar.provider.CreateEventResponse;
import com.daedalussystems.easySchedule.calendar.provider.GoogleCalendarProvider;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventResponse;
import com.daedalussystems.easySchedule.calendar.provider.DeleteEventRequest;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarProviderClientTest {
    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private UserRepository userRepository;
    @Mock private CalendarConnectionRepository connectionRepository;
    @Mock private GoogleCalendarProvider googleCalendarProvider;

    private GoogleCalendarProviderClient client;

    @BeforeEach
    void setUp() {
        client = new GoogleCalendarProviderClient(
                bookingRepository, eventTypeRepository, userRepository, connectionRepository, googleCalendarProvider);
    }

    @Test
    void createEvent_usesActiveConnectionAndReturnsExternalId() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest User")
                .build();
        EventType eventType = EventType.builder().id(eventTypeId).userId(hostId).name("30 min Intro").build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(googleCalendarProvider.createEvent(any())).thenReturn(new CreateEventResponse("ext-1", "https://calendar.google.com/event?eid=1", "https://meet.google.com/a"));

        var created = client.createEvent(bookingId, "google", "idem-1");
        assertEquals("ext-1", created.externalEventId());
        assertEquals("https://calendar.google.com/event?eid=1", created.providerEventUrl());
        assertEquals("https://meet.google.com/a", created.conferenceUrl());
        ArgumentCaptor<CreateEventRequest> requestCaptor = ArgumentCaptor.forClass(CreateEventRequest.class);
        verify(googleCalendarProvider).createEvent(requestCaptor.capture());
        CreateEventRequest sent = requestCaptor.getValue();
        assertEquals("host@example.com", sent.organizerEmail());
        assertEquals("guest@example.com", sent.attendeeEmail());
    }

    @Test
    void createEvent_missingGuestEmailFailsFast() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("  ")
                .build();
        EventType eventType = EventType.builder().id(eventTypeId).userId(hostId).name("30 min Intro").build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));

        CalendarClientException ex = assertThrows(CalendarClientException.class,
                () -> client.createEvent(bookingId, "google", "idem-1"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void updateEvent_propagatesGuestAttendeeDetails() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest User")
                .build();
        EventType eventType = EventType.builder().id(eventTypeId).userId(hostId).name("30 min Intro").build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(googleCalendarProvider.updateEvent(any())).thenReturn(new UpdateEventResponse("ext-2", "https://calendar.google.com/event?eid=2", "https://meet.google.com/b"));

        String externalId = client.updateEvent(bookingId, "google", "ext-1", "idem-1");
        assertEquals("ext-2", externalId);

        ArgumentCaptor<UpdateEventRequest> requestCaptor = ArgumentCaptor.forClass(UpdateEventRequest.class);
        verify(googleCalendarProvider).updateEvent(requestCaptor.capture());
        UpdateEventRequest sent = requestCaptor.getValue();
        assertEquals("guest@example.com", sent.attendeeEmail());
        assertEquals("Guest User", sent.attendeeName());
        verify(bookingRepository).findAnyById(eq(bookingId));
    }

    @Test
    void eventExists_usesProviderTruth() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = Booking.builder()
                .id(bookingId)
                .hostId(hostId)
                .eventTypeId(UUID.randomUUID())
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest User")
                .build();
        CalendarConnection connection = new CalendarConnection();
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(connectionRepository.findByUserIdAndProvider(hostId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(connection));
        when(googleCalendarProvider.eventExists(any(DeleteEventRequest.class))).thenReturn(false);

        boolean exists = client.eventExists(bookingId, "google", "ext-1");

        assertEquals(false, exists);
        verify(googleCalendarProvider).eventExists(any(DeleteEventRequest.class));
    }
}
