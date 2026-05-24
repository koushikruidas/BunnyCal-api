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
import com.daedalussystems.easySchedule.calendar.provider.MicrosoftCalendarProvider;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventResponse;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionCalendarRepository;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
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
class MicrosoftCalendarProviderClientTest {
    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private UserRepository userRepository;
    @Mock private CalendarConnectionRepository connectionRepository;
    @Mock private CalendarConnectionCalendarRepository calendarRepository;
    @Mock private MicrosoftCalendarProvider microsoftCalendarProvider;
    @Mock private com.daedalussystems.easySchedule.calendar.service.CalendarConnectionWriteService connectionWriteService;

    private MicrosoftCalendarProviderClient client;

    @BeforeEach
    void setUp() {
        client = new MicrosoftCalendarProviderClient(
                bookingRepository, eventTypeRepository, userRepository,
                connectionRepository, calendarRepository, microsoftCalendarProvider, connectionWriteService);
    }

    @Test
    void providerType_isMicrosoft() {
        assertEquals(CalendarProviderType.MICROSOFT, client.providerType());
    }

    @Test
    void createEvent_usesActiveConnectionAndReturnsExternalId() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest User")
                .build();
        EventType eventType = EventType.builder().id(eventTypeId).userId(hostId).name("30 min Intro").build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        // connection id not settable (Hibernate-managed); connection presence is sufficient
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(calendarRepository.findByConnectionIdAndSelectedTrue(any())).thenReturn(Optional.empty());
        when(microsoftCalendarProvider.createEvent(any())).thenReturn(
                new CreateEventResponse("ms-ext-1", "https://outlook.live.com/calendar/event?eid=1", null));

        var created = client.createEvent(bookingId, "microsoft", "idem-1", ConferencingInstruction.none());

        assertEquals("ms-ext-1", created.externalEventId());
        assertEquals("https://outlook.live.com/calendar/event?eid=1", created.providerEventUrl());

        ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
        verify(microsoftCalendarProvider).createEvent(captor.capture());
        CreateEventRequest sent = captor.getValue();
        assertEquals("host@example.com", sent.organizerEmail());
        assertEquals("guest@example.com", sent.attendeeEmail());
        assertEquals("Guest User", sent.attendeeName());
        assertEquals("30 min Intro", sent.title());
    }

    @Test
    void createEvent_withTeamsConferencing_embedsOnlineMeetingInstruction() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest")
                .build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        // connection id not settable (Hibernate-managed); connection presence is sufficient
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId))).thenReturn(Optional.empty());
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(calendarRepository.findByConnectionIdAndSelectedTrue(any())).thenReturn(Optional.empty());
        when(microsoftCalendarProvider.createEvent(any())).thenReturn(
                new CreateEventResponse("ms-ext-2", null, "https://teams.microsoft.com/l/meetup-join/..."));

        ConferencingInstruction teamsInstruction = ConferencingInstruction.requestNativeMeet(
                ConferencingProviderType.MICROSOFT_TEAMS);

        var created = client.createEvent(bookingId, "microsoft", "idem-2", teamsInstruction);

        assertEquals("ms-ext-2", created.externalEventId());
        assertEquals("https://teams.microsoft.com/l/meetup-join/...", created.conferenceUrl());

        ArgumentCaptor<CreateEventRequest> captor = ArgumentCaptor.forClass(CreateEventRequest.class);
        verify(microsoftCalendarProvider).createEvent(captor.capture());
        CreateEventRequest sent = captor.getValue();
        assertEquals(ConferencingInstruction.Mode.REQUEST_NATIVE_MEET, sent.conferencingInstruction().mode());
        assertEquals(ConferencingProviderType.MICROSOFT_TEAMS, sent.conferencingInstruction().providerType());
    }

    @Test
    void createEvent_missingGuestEmailFailsFast() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("  ")
                .build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        // connection id not settable (Hibernate-managed); connection presence is sufficient
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId))).thenReturn(Optional.empty());
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));

        CalendarClientException ex = assertThrows(CalendarClientException.class,
                () -> client.createEvent(bookingId, "microsoft", "idem-1", ConferencingInstruction.none()));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void createEvent_noActiveConnection_failsWith404() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId))).thenReturn(Optional.empty());
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        CalendarClientException ex = assertThrows(CalendarClientException.class,
                () -> client.createEvent(bookingId, "microsoft", "idem-1", ConferencingInstruction.none()));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void updateEvent_propagatesGuestAttendeeDetails() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest User")
                .build();
        EventType eventType = EventType.builder().id(eventTypeId).userId(hostId).name("30 min Intro").build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = new CalendarConnection();
        // connection id not settable (Hibernate-managed); connection presence is sufficient
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(eventType));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(calendarRepository.findByConnectionIdAndSelectedTrue(any())).thenReturn(Optional.empty());
        when(microsoftCalendarProvider.updateEvent(any())).thenReturn(new UpdateEventResponse("ms-ext-2", null, null));

        String externalId = client.updateEvent(bookingId, "microsoft", "ms-ext-1", "idem-2", ConferencingInstruction.none());
        assertEquals("ms-ext-2", externalId);

        ArgumentCaptor<UpdateEventRequest> captor = ArgumentCaptor.forClass(UpdateEventRequest.class);
        verify(microsoftCalendarProvider).updateEvent(captor.capture());
        UpdateEventRequest sent = captor.getValue();
        assertEquals("guest@example.com", sent.attendeeEmail());
        assertEquals("Guest User", sent.attendeeName());
        assertEquals("ms-ext-1", sent.externalEventId());
        verify(bookingRepository).findAnyById(eq(bookingId));
    }

    // ── Organizer mailbox classification (MSA vs AAD) ────────────────────────

    @Test
    void createEvent_msaOrganizer_stampsBackendIcsFallback() {
        runCreateAndCaptureCapability(
                "host@outlook.com",
                "PERSONAL_MSA",
                "BACKEND_ICS_FALLBACK");
    }

    @Test
    void createEvent_hotmailOrganizer_stampsBackendIcsFallback() {
        runCreateAndCaptureCapability(
                "host@hotmail.com",
                "PERSONAL_MSA",
                "BACKEND_ICS_FALLBACK");
    }

    @Test
    void createEvent_liveOrganizer_stampsBackendIcsFallback() {
        runCreateAndCaptureCapability(
                "host@live.com",
                "PERSONAL_MSA",
                "BACKEND_ICS_FALLBACK");
    }

    @Test
    void createEvent_aadOrganizer_stampsProviderNative() {
        runCreateAndCaptureCapability(
                "host@contoso.onmicrosoft.com",
                "AAD_WORK_SCHOOL",
                "PROVIDER_NATIVE");
    }

    @Test
    void createEvent_missingOrganizer_stampsUnknown() {
        runCreateAndCaptureCapability(
                null,
                "UNKNOWN",
                "UNKNOWN");
    }

    private void runCreateAndCaptureCapability(String organizerEmail,
                                               String expectedClassification,
                                               String expectedDelivery) {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .guestName("Guest")
                .build();
        User host = User.builder().id(hostId).email("host@example.com").name("Host").timezone("UTC").build();
        CalendarConnection connection = connectionWithId(connectionId);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId))).thenReturn(Optional.empty());
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(connectionRepository.findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(connection));
        when(calendarRepository.findByConnectionIdAndSelectedTrue(any())).thenReturn(Optional.empty());
        when(microsoftCalendarProvider.createEvent(any())).thenReturn(
                new com.daedalussystems.easySchedule.calendar.provider.CreateEventResponse(
                        "ms-ext-x", "https://outlook.live.com/x", null, organizerEmail));

        client.createEvent(bookingId, "microsoft", "idem-x", ConferencingInstruction.none());

        verify(connectionWriteService).stampAccountCapability(
                eq(connectionId),
                eq(expectedClassification),
                eq(expectedDelivery),
                eq("microsoft_create_event_organizer_classified"));
    }

    private static CalendarConnection connectionWithId(UUID id) {
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(conn, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return conn;
    }
}
