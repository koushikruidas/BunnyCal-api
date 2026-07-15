package io.bunnycal.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;

import io.bunnycal.conferencing.service.ConferencingExecutionPolicy;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventTypeOrchestrationNormalizerTest {

    private EventTypeOrchestrationNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new EventTypeOrchestrationNormalizer();
    }

    @Test
    void customConferenceUrlRequiresHttps() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "custom_url", "http://insecure.test"),
                null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(request));
    }

    @Test
    void customConferenceUrl_httpsAccepted() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "custom_url", "https://secure.test/room"),
                null
        );

        EventTypeOrchestrationNormalizer.ConferencingConfig config = normalizer.normalize(request);

        assertTrue(config.enabled());
        assertEquals(ConferencingProviderType.CUSTOM_URL, config.providerType());
        assertEquals("https://secure.test/room", config.customUrl());
    }

    // Meet can only be minted on a Google calendar, so an event type that froze it would break the
    // day its owner moved their write-back calendar. It is reachable only through DEFAULT.
    @Test
    void googleMeet_cannotBePinnedToAnEventType() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Cross Provider", null, null, 30, 0, 0, 30, 0, 30, 10, "cross-provider",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "google_meet", null),
                null
        );

        CustomException ex = assertThrows(CustomException.class, () -> normalizer.normalize(request));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void microsoftTeams_cannotBePinnedToAnEventType() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Teams", null, null, 30, 0, 0, 30, 0, 30, 10, "teams",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "microsoft_teams", null),
                null
        );

        CustomException ex = assertThrows(CustomException.class, () -> normalizer.normalize(request));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void conferencingExecutionPolicy_rejectsGoogleMeetWithMicrosoftMirrorProvider() {
        ConferencingExecutionPolicy policy = new ConferencingExecutionPolicy(Mockito.mock(CalendarConnectionRepository.class));
        ConferencingInstruction instruction = ConferencingInstruction.requestNativeMeet(ConferencingProviderType.GOOGLE_MEET);

        assertThrows(CustomException.class,
                () -> policy.adaptForMirrorProvider(instruction, "microsoft", UUID.randomUUID(), "CREATE"));
    }

    @Test
    void conferencingExecutionPolicy_rejectsMicrosoftTeamsWithGoogleMirrorProvider() {
        ConferencingExecutionPolicy policy = new ConferencingExecutionPolicy(Mockito.mock(CalendarConnectionRepository.class));
        ConferencingInstruction instruction = ConferencingInstruction.requestNativeMeet(ConferencingProviderType.MICROSOFT_TEAMS);

        assertThrows(CustomException.class,
                () -> policy.adaptForMirrorProvider(instruction, "google", UUID.randomUUID(), "CREATE"));
    }

    @Test
    void conferencingDisabled_yieldsNone() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                null
        );

        EventTypeOrchestrationNormalizer.ConferencingConfig config = normalizer.normalize(request);

        assertFalse(config.enabled());
        assertEquals(ConferencingProviderType.NONE, config.providerType());
        assertNull(config.customUrl());
    }

    // "A meeting link, but you didn't say which" — the user's own global default is the answer.
    @Test
    void conferencingEnabledWithoutProvider_yieldsDefaultPointer() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, null, null),
                null
        );

        EventTypeOrchestrationNormalizer.ConferencingConfig config = normalizer.normalize(request);

        assertTrue(config.enabled());
        assertEquals(ConferencingProviderType.DEFAULT, config.providerType());
        assertTrue(config.providerType().isPointer());
    }

    @Test
    void conferencingProviderNone_withEnabledTrue_rejected() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "none", null),
                null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(request));
    }

    @Test
    void unknownConferencingProvider_rejected() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Intro", null, null, 30, 0, 0, 30, 0, 30, 10, "intro",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "webex", null),
                null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(request));
    }

    // ── kind-independence: conferencing normalisation does not branch on event kind ───────────

    @Test
    void roundRobin_withZoomConferencing_succeeds() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "RR Zoom", null, null, 30, 0, 0, 30, 0, 30, 10, "rr-zoom",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "zoom", null),
                null,
                EventKind.ROUND_ROBIN, null
        );

        EventTypeOrchestrationNormalizer.ConferencingConfig config = normalizer.normalize(request);

        assertEquals(ConferencingProviderType.ZOOM, config.providerType());
    }

    @Test
    void roundRobin_withGoogleMeetConferencing_rejected() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "RR Google Meet", null, null, 30, 0, 0, 30, 0, 30, 10, "rr-gmeet",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "google_meet", null),
                null,
                EventKind.ROUND_ROBIN, null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(request));
    }

    @Test
    void roundRobin_withMicrosoftTeamsConferencing_rejected() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "RR Teams", null, null, 30, 0, 0, 30, 0, 30, 10, "rr-teams",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(true, "microsoft_teams", null),
                null,
                EventKind.ROUND_ROBIN, null
        );

        assertThrows(CustomException.class, () -> normalizer.normalize(request));
    }

    @Test
    void group_conferencingDisabled_yieldsNone() {
        CreateEventTypeRequest request = new CreateEventTypeRequest(
                "Group", null, null, 30, 0, 0, 30, 0, 30, 10, "group",
                List.of(),
                new CreateEventTypeRequest.ConferenceRequest(false, null, null),
                null,
                EventKind.GROUP, null
        );

        assertEquals(ConferencingProviderType.NONE, normalizer.normalize(request).providerType());
    }

    // ── draft mutation: a null conference block means "leave it alone" ────────────────────────

    @Test
    void draftMutation_nullConference_roundTripsExistingChoice() {
        EventType existing = EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("Intro")
                .conferencingProvider(ConferencingProviderType.ZOOM)
                .build();

        EventTypeOrchestrationNormalizer.ConferencingConfig config =
                normalizer.normalizeForDraftMutation(existing, null);

        assertTrue(config.enabled());
        assertEquals(ConferencingProviderType.ZOOM, config.providerType());
    }

    @Test
    void draftMutation_nullConference_onNoneEventType_staysNone() {
        EventType existing = EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("Intro")
                .conferencingProvider(ConferencingProviderType.NONE)
                .build();

        EventTypeOrchestrationNormalizer.ConferencingConfig config =
                normalizer.normalizeForDraftMutation(existing, null);

        assertFalse(config.enabled());
        assertEquals(ConferencingProviderType.NONE, config.providerType());
    }

    @Test
    void draftMutation_explicitConference_overridesExistingChoice() {
        EventType existing = EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("Intro")
                .conferencingProvider(ConferencingProviderType.ZOOM)
                .build();

        EventTypeOrchestrationNormalizer.ConferencingConfig config = normalizer.normalizeForDraftMutation(
                existing, new CreateEventTypeRequest.ConferenceRequest(true, null, null));

        assertEquals(ConferencingProviderType.DEFAULT, config.providerType());
    }
}
