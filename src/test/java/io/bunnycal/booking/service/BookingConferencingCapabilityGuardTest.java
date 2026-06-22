package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingConferencingCapabilityGuardTest {

    @Mock
    private EventTypeRepository eventTypeRepository;
    @Mock
    private CalendarConnectionRepository calendarConnectionRepository;

    private BookingConferencingCapabilityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BookingConferencingCapabilityGuard(eventTypeRepository, calendarConnectionRepository);
    }

    // ── ROUND_ROBIN — all conferencing providers must pass ──────────────────

    @Test
    void roundRobin_withGoogleMeet_andNullProjection_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(rrEventType(eventTypeId, ConferencingProviderType.GOOGLE_MEET, null)));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(bookingId, participantId, eventTypeId));
    }

    @Test
    void roundRobin_withMicrosoftTeams_andNullProjection_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(rrEventType(eventTypeId, ConferencingProviderType.MICROSOFT_TEAMS, null)));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(bookingId, participantId, eventTypeId));
    }

    @Test
    void roundRobin_withZoom_andNullProjection_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(rrEventType(eventTypeId, ConferencingProviderType.ZOOM, null)));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(bookingId, participantId, eventTypeId));
    }

    @Test
    void roundRobin_withGoogleMeet_andMicrosoftProjection_doesNotThrow() {
        // Even if an RR event type somehow has a Microsoft projection (migration artifact),
        // the guard must not fire — conferencing ownership is participant-derived.
        UUID eventTypeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(rrEventType(eventTypeId, ConferencingProviderType.GOOGLE_MEET, CalendarProviderType.MICROSOFT)));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(bookingId, participantId, eventTypeId));
    }

    @Test
    void roundRobin_withNoConferencing_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(rrEventType(eventTypeId, ConferencingProviderType.NONE, null)));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(bookingId, participantId, eventTypeId));
    }

    // ── ONE_ON_ONE — projection provider checks must still fire ────────────

    @Test
    void oneOnOne_withGoogleMeet_andGoogleProjection_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(
                ownerEventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.GOOGLE_MEET, CalendarProviderType.GOOGLE, connId)));
        when(calendarConnectionRepository.findById(connId)).thenReturn(Optional.of(googleConnection(connId)));

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), UUID.randomUUID(), eventTypeId));
    }

    @Test
    void oneOnOne_withGoogleMeet_andMicrosoftProjection_throws() {
        UUID eventTypeId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(
                ownerEventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.GOOGLE_MEET, CalendarProviderType.MICROSOFT, connId)));

        assertThrows(CustomException.class,
                () -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), UUID.randomUUID(), eventTypeId));
    }

    @Test
    void oneOnOne_withMicrosoftTeams_andGoogleProjection_throws() {
        UUID eventTypeId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(
                ownerEventType(eventTypeId, EventKind.ONE_ON_ONE, ConferencingProviderType.MICROSOFT_TEAMS, CalendarProviderType.GOOGLE, connId)));

        assertThrows(CustomException.class,
                () -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), UUID.randomUUID(), eventTypeId));
    }

    // ── Unknown event type → safe no-op ────────────────────────────────────

    @Test
    void unknownEventType_doesNotThrow() {
        UUID eventTypeId = UUID.randomUUID();
        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> guard.assertBookingConfirmationSupported(UUID.randomUUID(), UUID.randomUUID(), eventTypeId));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static EventType rrEventType(UUID id, ConferencingProviderType conferencing, CalendarProviderType projectionProvider) {
        return EventType.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .name("RR Event")
                .kind(EventKind.ROUND_ROBIN)
                .conferencingProvider(conferencing)
                .projectionProvider(projectionProvider)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(10))
                .build();
    }

    private static EventType ownerEventType(UUID id, EventKind kind, ConferencingProviderType conferencing,
                                             CalendarProviderType projectionProvider, UUID projectionConnectionId) {
        return EventType.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .name("Owner Event")
                .kind(kind)
                .conferencingProvider(conferencing)
                .projectionProvider(projectionProvider)
                .projectionConnectionId(projectionConnectionId)
                .projectionCalendarId("primary")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(10))
                .build();
    }

    private static CalendarConnection googleConnection(UUID id) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(UUID.randomUUID());
        c.setProvider(CalendarProviderType.GOOGLE);
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        c.setProviderUserId("google-user-123");
        c.setRefreshTokenCiphertext("cipher");
        c.setScopes(List.of("calendar.readwrite"));
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (Exception ignored) {}
        return c;
    }
}
