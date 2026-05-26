package io.bunnycal.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.bunnycal.conferencing.service.ConferencingExecutionPolicy;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventTypeOrchestrationNormalizerTest {

    @Mock
    private CalendarConnectionRepository calendarConnectionRepository;
    @Mock
    private CalendarConnectionCalendarRepository calendarRepository;

    private EventTypeOrchestrationNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new EventTypeOrchestrationNormalizer(calendarConnectionRepository, calendarRepository, new SimpleMeterRegistry());
    }

    @Test
    void projectionDestination_isExplicitAndIndependentOfAvailabilityOrder() {
        UUID userId = UUID.randomUUID();
        UUID oldConnId = UUID.randomUUID();
        UUID newConnId = UUID.randomUUID();

        CalendarConnection oldConn = activeConnection(userId, oldConnId, CalendarProviderType.GOOGLE);
        CalendarConnection newConn = activeConnection(userId, newConnId, CalendarProviderType.MICROSOFT);

        when(calendarConnectionRepository.findById(newConnId)).thenReturn(Optional.of(newConn));
        when(calendarConnectionRepository.findById(oldConnId)).thenReturn(Optional.of(oldConn));
        when(calendarRepository.findByConnectionIdAndExternalCalendarId(oldConnId, "proj-g"))
                .thenReturn(Optional.of(writableCalendar(oldConnId, "proj-g")));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Ordering Test", null, null, 30, 0, 0, 30, 0, 30, 10, "ordering-test",
                List.of(
                        new CreateEventTypeRequest.AvailabilityCalendarRequest(newConnId.toString(), "microsoft", "cal_m"),
                        new CreateEventTypeRequest.AvailabilityCalendarRequest(oldConnId.toString(), "google", "cal_g")
                ),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", oldConnId, "proj-g")
        );

        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);

        assertEquals(oldConnId, normalized.projectionDestination().connectionId());
        assertEquals("google", normalized.projectionDestination().provider());
        // Both availability bindings preserved, original order intact
        assertEquals(2, normalized.availabilityBindings().size());
        assertEquals(newConnId, normalized.availabilityBindings().get(0).connectionId());
        assertEquals(oldConnId, normalized.availabilityBindings().get(1).connectionId());
    }

    @Test
    void availabilityBindings_allPreserved_withoutAffectingProjectionDestination() {
        UUID userId = UUID.randomUUID();
        UUID googleConnId = UUID.randomUUID();
        UUID msConnId = UUID.randomUUID();
        CalendarConnection googleConn = activeConnection(userId, googleConnId, CalendarProviderType.GOOGLE);
        CalendarConnection msConn = activeConnection(userId, msConnId, CalendarProviderType.MICROSOFT);

        when(calendarConnectionRepository.findById(googleConnId)).thenReturn(Optional.of(googleConn));
        when(calendarConnectionRepository.findById(msConnId)).thenReturn(Optional.of(msConn));
        when(calendarRepository.findByConnectionIdAndExternalCalendarId(googleConnId, "proj-g"))
                .thenReturn(Optional.of(writableCalendar(googleConnId, "proj-g")));
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Multi", null, null, 30, 0, 0, 30, 0, 30, 10, "multi",
                List.of(
                        new CreateEventTypeRequest.AvailabilityCalendarRequest(googleConnId.toString(), "google", "cal_g"),
                        new CreateEventTypeRequest.AvailabilityCalendarRequest(msConnId.toString(), "microsoft", "cal_m")
                ),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", googleConnId, "proj-g")
        );

        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);

        assertEquals(googleConnId, normalized.projectionDestination().connectionId());
        assertEquals(2, normalized.availabilityBindings().size());
        assertEquals(googleConnId, normalized.availabilityBindings().get(0).connectionId());
        assertEquals(msConnId, normalized.availabilityBindings().get(1).connectionId());
    }

    @Test
    void missingProjectionDestination_throwsValidationError() {
        UUID userId = UUID.randomUUID();

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    @Test
    void availabilityConnectionsAreValidatedAsReadOnlyBindings() {
        UUID userId = UUID.randomUUID();
        UUID availabilityConnectionId = UUID.randomUUID();
        CalendarConnection conn = activeConnection(userId, availabilityConnectionId, CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findById(availabilityConnectionId)).thenReturn(Optional.of(conn));
        when(calendarRepository.findByConnectionIdAndExternalCalendarId(availabilityConnectionId, "proj-g"))
                .thenReturn(Optional.of(writableCalendar(availabilityConnectionId, "proj-g")));
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(new CreateEventTypeRequest.AvailabilityCalendarRequest(availabilityConnectionId.toString(), "google", "cal_1")),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", availabilityConnectionId, "proj-g")
        );

        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);

        assertEquals(1, normalized.availabilityBindings().size());
        assertEquals(availabilityConnectionId, normalized.availabilityBindings().get(0).connectionId());
        assertEquals(ConferencingProviderType.NONE, normalized.conferencing().provider());
    }

    @Test
    void customConferenceUrlRequiresHttps() {
        UUID userId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "custom_url", "http://insecure.test"),
                projection("google", connId, "proj-g")
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    @Test
    void decoupledMode_allowsGoogleMeetWithMicrosoftProjectionDestination() {
        UUID userId = UUID.randomUUID();
        UUID msConnId = UUID.randomUUID();
        CalendarConnection msConn = activeConnection(userId, msConnId, CalendarProviderType.MICROSOFT);
        when(calendarConnectionRepository.findById(msConnId)).thenReturn(Optional.of(msConn));
        when(calendarConnectionRepository.findById(msConnId)).thenReturn(Optional.of(msConn));
        when(calendarRepository.findByConnectionIdAndExternalCalendarId(msConnId, "proj-m"))
                .thenReturn(Optional.of(writableCalendar(msConnId, "proj-m")));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Cross Provider", null, null, 30, 0, 0, 30, 0, 30, 10, "cross-provider",
                List.of(new CreateEventTypeRequest.AvailabilityCalendarRequest(msConnId.toString(), "microsoft", null)),
                new CreateEventTypeRequest.ConferenceRequest(true, "google_meet", null),
                projection("microsoft", msConnId, "proj-m")
        );

        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);
        assertEquals(ConferencingProviderType.GOOGLE_MEET, normalized.conferencing().provider());
        assertEquals(msConnId, normalized.projectionDestination().connectionId());
    }

    @Test
    void conferencingExecutionPolicy_rejectsGoogleMeetWithMicrosoftMirrorProvider() {
        ConferencingExecutionPolicy policy =
                new ConferencingExecutionPolicy();
        ConferencingInstruction instruction =
                ConferencingInstruction.requestNativeMeet(
                        ConferencingProviderType.GOOGLE_MEET);

        assertThrows(CustomException.class,
                () -> policy.adaptForMirrorProvider(instruction, "microsoft", UUID.randomUUID(), "CREATE"));
    }

    @Test
    void conferencingExecutionPolicy_rejectsMicrosoftTeamsWithGoogleMirrorProvider() {
        ConferencingExecutionPolicy policy =
                new ConferencingExecutionPolicy();
        ConferencingInstruction instruction =
                ConferencingInstruction.requestNativeMeet(
                        ConferencingProviderType.MICROSOFT_TEAMS);

        assertThrows(CustomException.class,
                () -> policy.adaptForMirrorProvider(instruction, "google", UUID.randomUUID(), "CREATE"));
    }

    @Test
    void invalidAvailabilityConnection_throwsValidationError() {
        UUID userId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();
        when(calendarConnectionRepository.findById(unknownId)).thenReturn(Optional.empty());

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(new CreateEventTypeRequest.AvailabilityCalendarRequest(unknownId.toString(), "google", null)),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", unknownId, "proj-g")
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    @Test
    void nonUuidAvailabilityConnectionId_throwsValidationError() {
        UUID userId = UUID.randomUUID();
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(new CreateEventTypeRequest.AvailabilityCalendarRequest("google", "google", null)),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", UUID.randomUUID(), "proj-g")
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    @Test
    void projectionDestination_missingCalendar_rejected() {
        UUID userId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        CalendarConnection conn = activeConnection(userId, connId, CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findById(connId)).thenReturn(Optional.of(conn));
        when(calendarRepository.findByConnectionIdAndExternalCalendarId(connId, "missing")).thenReturn(Optional.empty());

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", connId, "missing")
        );
        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    @Test
    void projectionDestination_readOnlyCalendar_rejected() {
        UUID userId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        CalendarConnection conn = activeConnection(userId, connId, CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findById(connId)).thenReturn(Optional.of(conn));
        CalendarConnectionCalendar readOnly = writableCalendar(connId, "cal");
        readOnly.setSyncEnabled(false);
        when(calendarRepository.findByConnectionIdAndExternalCalendarId(connId, "cal")).thenReturn(Optional.of(readOnly));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                projection("google", connId, "cal")
        );
        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    private static CalendarConnection activeConnection(UUID userId, UUID id, CalendarProviderType provider) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(userId);
        c.setProvider(provider);
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        c.setProviderUserId("provider-user");
        c.setRefreshTokenCiphertext("cipher");
        c.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        c.setScopes(List.of("calendar.readwrite"));
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (Exception ignored) {
        }
        return c;
    }

    private static CalendarConnectionCalendar writableCalendar(UUID connectionId, String externalCalendarId) {
        CalendarConnectionCalendar calendar = new CalendarConnectionCalendar();
        calendar.setConnectionId(connectionId);
        calendar.setExternalCalendarId(externalCalendarId);
        calendar.setSyncEnabled(true);
        return calendar;
    }

    private static CreateEventTypeRequest.ProjectionDestinationRequest projection(String provider, UUID connectionId, String calendarId) {
        return new CreateEventTypeRequest.ProjectionDestinationRequest(provider, connectionId.toString(), calendarId);
    }
}
