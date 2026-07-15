package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import io.bunnycal.conferencing.service.NativeConferencingCapabilityService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The guard asks one question, of every event kind alike: can the calendar that will receive this
 * booking actually mint the meeting link the event type resolves to? The <em>writer</em> is the
 * booking's host — the owner for 1:1/group/collective, the assigned member for round-robin — so
 * there is no kind-specific branch left to test.
 */
@ExtendWith(MockitoExtension.class)
class BookingConferencingCapabilityGuardTest {

    @Mock
    private EventTypeRepository eventTypeRepository;
    @Mock
    private BookingSchedulingProjectionResolver projectionResolver;
    @Mock
    private EventConferencingResolver conferencingResolver;
    @Mock
    private CalendarConnectionCalendarRepository calendarRepository;

    private BookingConferencingCapabilityGuard guard;

    @BeforeEach
    void setUp() {
        CalendarConnectionCalendar teamsCalendar = new CalendarConnectionCalendar();
        teamsCalendar.setSupportsNativeTeams(true);
        org.mockito.Mockito.lenient().when(
                calendarRepository.findByConnectionIdAndSelectedTrue(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(teamsCalendar));
        guard = new BookingConferencingCapabilityGuard(
                eventTypeRepository, projectionResolver, conferencingResolver,
                new NativeConferencingCapabilityService(calendarRepository));
    }

    // ── ROUND_ROBIN — resolved against the ASSIGNED MEMBER's own calendar ───

    @Test
    void roundRobin_meetResolvedForAssignedMember_withGoogleCalendar_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ROUND_ROBIN, ConferencingProviderType.DEFAULT);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(participantId, eventType))
                .thenReturn(ConferencingProviderType.GOOGLE_MEET);
        when(projectionResolver.writebackConnection(participantId))
                .thenReturn(Optional.of(connection(CalendarProviderType.GOOGLE, "google-user-123")));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(
                UUID.randomUUID(), participantId, eventTypeId));
    }

    @Test
    void roundRobin_teamsResolvedForAssignedMember_withMicrosoft365Calendar_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ROUND_ROBIN, ConferencingProviderType.DEFAULT);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(participantId, eventType))
                .thenReturn(ConferencingProviderType.MICROSOFT_TEAMS);
        when(projectionResolver.writebackConnection(participantId)).thenReturn(
                Optional.of(connection(CalendarProviderType.MICROSOFT,
                        "12345678-1234-1234-1234-123456789012")));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(
                UUID.randomUUID(), participantId, eventTypeId));
    }

    @Test
    void roundRobin_withZoom_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ROUND_ROBIN, ConferencingProviderType.ZOOM);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(participantId, eventType))
                .thenReturn(ConferencingProviderType.ZOOM);

        // Zoom needs no calendar provider, so the write-back calendar is never consulted.
        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(
                UUID.randomUUID(), participantId, eventTypeId));
    }

    @Test
    void roundRobin_withNoConferencing_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ROUND_ROBIN, ConferencingProviderType.NONE);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(participantId, eventType))
                .thenReturn(ConferencingProviderType.NONE);

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(
                UUID.randomUUID(), participantId, eventTypeId));
    }

    // ── ONE_ON_ONE — provider capability checks must still fire ────────────

    @Test
    void oneOnOne_withGoogleMeet_andGoogleWriteback_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.DEFAULT);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(hostId, eventType))
                .thenReturn(ConferencingProviderType.GOOGLE_MEET);
        when(projectionResolver.writebackConnection(hostId))
                .thenReturn(Optional.of(connection(CalendarProviderType.GOOGLE, "google-user-123")));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(
                UUID.randomUUID(), hostId, eventTypeId));
    }

    @Test
    void oneOnOne_withGoogleMeet_andMicrosoftWriteback_throws() {
        UUID eventTypeId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.DEFAULT);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(hostId, eventType))
                .thenReturn(ConferencingProviderType.GOOGLE_MEET);
        when(projectionResolver.writebackConnection(hostId)).thenReturn(
                Optional.of(connection(CalendarProviderType.MICROSOFT,
                        "12345678-1234-1234-1234-123456789012")));

        assertThrows(CustomException.class,
                () -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), hostId, eventTypeId));
    }

    @Test
    void oneOnOne_withMicrosoftTeams_andGoogleWriteback_throws() {
        UUID eventTypeId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.DEFAULT);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(hostId, eventType))
                .thenReturn(ConferencingProviderType.MICROSOFT_TEAMS);
        when(projectionResolver.writebackConnection(hostId))
                .thenReturn(Optional.of(connection(CalendarProviderType.GOOGLE, "google-user-123")));

        assertThrows(CustomException.class,
                () -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), hostId, eventTypeId));
    }

    // A native meeting link with no calendar at all to mint it on cannot be honoured.
    @Test
    void nativeMeetingLink_withNoWritebackCalendar_throws() {
        UUID eventTypeId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        EventType eventType = eventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.DEFAULT);

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(conferencingResolver.resolve(hostId, eventType))
                .thenReturn(ConferencingProviderType.GOOGLE_MEET);
        when(projectionResolver.writebackConnection(hostId)).thenReturn(Optional.empty());

        assertThrows(CustomException.class,
                () -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), hostId, eventTypeId));
    }

    // ── Unknown event type → safe no-op ────────────────────────────────────

    @Test
    void unknownEventType_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(
                UUID.randomUUID(), UUID.randomUUID(), eventTypeId));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static EventType eventType(UUID id, EventKind kind, ConferencingProviderType conferencing) {
        return EventType.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .name("Event")
                .kind(kind)
                .conferencingProvider(conferencing)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(10))
                .build();
    }

    private static CalendarConnection connection(CalendarProviderType provider, String providerUserId) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(UUID.randomUUID());
        c.setProvider(provider);
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        c.setProviderUserId(providerUserId);
        c.setRefreshTokenCiphertext("cipher");
        c.setScopes(List.of("calendar.readwrite"));
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, UUID.randomUUID());
        } catch (Exception ignored) {}
        return c;
    }
}
