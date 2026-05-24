package com.daedalussystems.easySchedule.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.dto.CreateEventTypeRequest;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventTypeOrchestrationNormalizerTest {

    @Mock
    private CalendarConnectionRepository calendarConnectionRepository;

    private EventTypeOrchestrationNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new EventTypeOrchestrationNormalizer(calendarConnectionRepository, false);
    }

    @Test
    void canonicalOrganizerConnectionTakesPrecedenceOverLegacyProvider() {
        UUID userId = UUID.randomUUID();
        UUID canonicalConnectionId = UUID.randomUUID();
        CalendarConnection canonicalConnection = activeConnection(userId, canonicalConnectionId, CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findById(canonicalConnectionId)).thenReturn(Optional.of(canonicalConnection));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                canonicalConnectionId,
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "zoom", null),
                null,
                "microsoft",
                "google_meet",
                null
        );

        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);
        assertEquals(canonicalConnectionId, normalized.authoritativeConnectionId());
        assertEquals(ConferencingProviderType.ZOOM, normalized.conferencing().provider());
    }

    @Test
    void availabilityConnectionsAreValidatedAsReadOnlyBindings() {
        UUID userId = UUID.randomUUID();
        UUID schedulerConnectionId = UUID.randomUUID();
        UUID availabilityConnectionId = UUID.randomUUID();
        when(calendarConnectionRepository.findById(schedulerConnectionId))
                .thenReturn(Optional.of(activeConnection(userId, schedulerConnectionId, CalendarProviderType.GOOGLE)));
        when(calendarConnectionRepository.findById(availabilityConnectionId))
                .thenReturn(Optional.of(activeConnection(userId, availabilityConnectionId, CalendarProviderType.GOOGLE)));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                schedulerConnectionId,
                List.of(new CreateEventTypeRequest.AvailabilityCalendarRequest(availabilityConnectionId, "google", "cal_1")),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                null,
                null,
                null,
                null
        );
        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);

        assertEquals(1, normalized.availabilityBindings().size());
        assertEquals(availabilityConnectionId, normalized.availabilityBindings().get(0).connectionId());
        assertEquals(ConferencingProviderType.NONE, normalized.conferencing().provider());
    }

    @Test
    void customConferenceUrlRequiresHttps() {
        UUID userId = UUID.randomUUID();
        UUID schedulerConnectionId = UUID.randomUUID();
        when(calendarConnectionRepository.findById(schedulerConnectionId))
                .thenReturn(Optional.of(activeConnection(userId, schedulerConnectionId, CalendarProviderType.GOOGLE)));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                schedulerConnectionId,
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "custom_url", "http://insecure.test"),
                null,
                null,
                null,
                null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(userId, request));
    }

    @Test
    void decoupledMode_allowsGoogleMeetWithMicrosoftAuthoritativeConnection() {
        UUID userId = UUID.randomUUID();
        UUID schedulerConnectionId = UUID.randomUUID();
        when(calendarConnectionRepository.findById(schedulerConnectionId))
                .thenReturn(Optional.of(activeConnection(userId, schedulerConnectionId, CalendarProviderType.MICROSOFT)));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Cross Provider", null, null, 30, 0, 0, 30, 0, 30, 10, "cross-provider",
                schedulerConnectionId,
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "google_meet", null),
                null,
                null,
                null,
                null
        );

        EventTypeOrchestrationNormalizer.NormalizedOrchestration normalized = normalizer.normalize(userId, request);
        assertEquals(ConferencingProviderType.GOOGLE_MEET, normalized.conferencing().provider());
        assertEquals(CalendarProviderType.MICROSOFT, normalized.authoritativeProvider());
    }

    @Test
    void strictCompatibilityMode_rejectsGoogleMeetWithMicrosoftAuthoritativeConnection() {
        EventTypeOrchestrationNormalizer strictNormalizer =
                new EventTypeOrchestrationNormalizer(calendarConnectionRepository, true);
        UUID userId = UUID.randomUUID();
        UUID schedulerConnectionId = UUID.randomUUID();
        when(calendarConnectionRepository.findById(schedulerConnectionId))
                .thenReturn(Optional.of(activeConnection(userId, schedulerConnectionId, CalendarProviderType.MICROSOFT)));

        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Cross Provider", null, null, 30, 0, 0, 30, 0, 30, 10, "cross-provider",
                schedulerConnectionId,
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "google_meet", null),
                null,
                null,
                null,
                null
        );

        assertThrows(CustomException.class, () -> strictNormalizer.normalize(userId, request));
    }

    private static CalendarConnection activeConnection(UUID userId, UUID id, CalendarProviderType provider) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(userId);
        c.setProvider(provider);
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        c.setProviderUserId("provider-user");
        c.setRefreshTokenCiphertext("cipher");
        c.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        c.setScopes(List.of("calendar.read"));
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (Exception ignored) {
        }
        return c;
    }
}
