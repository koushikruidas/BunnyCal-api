package com.daedalussystems.easySchedule.common.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConferencingProviderTypeTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "google_meet,GOOGLE_MEET",
            "google-meet,GOOGLE_MEET",
            "GOOGLE_MEET,GOOGLE_MEET",
            "GOOGLE-MEET,GOOGLE_MEET",
            "Google-Meet,GOOGLE_MEET",
            "google meet,GOOGLE_MEET",
            "  google_meet  ,GOOGLE_MEET",
            "zoom,ZOOM",
            "ZOOM,ZOOM",
            "Zoom,ZOOM",
            "microsoft_teams,MICROSOFT_TEAMS",
            "microsoft-teams,MICROSOFT_TEAMS",
            "Microsoft Teams,MICROSOFT_TEAMS",
            "custom_url,CUSTOM_URL",
            "Custom-Url,CUSTOM_URL",
            "none,NONE"
    })
    void fromExternal_normalizesCommonVariants(String raw, String expected) {
        Optional<ConferencingProviderType> result = ConferencingProviderType.fromExternal(raw);
        assertTrue(result.isPresent(), "expected " + raw + " to resolve");
        assertEquals(ConferencingProviderType.valueOf(expected), result.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "skype", "google_video", "??", "google__meet_x"})
    void fromExternal_returnsEmptyForUnknownInput(String raw) {
        assertTrue(ConferencingProviderType.fromExternal(raw).isEmpty(),
                "expected " + raw + " to not resolve");
    }

    @org.junit.jupiter.api.Test
    void fromExternal_returnsEmptyForNull() {
        assertTrue(ConferencingProviderType.fromExternal(null).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void externalId_isLowerCaseEnumName() {
        assertEquals("google_meet", ConferencingProviderType.GOOGLE_MEET.externalId());
        assertEquals("zoom", ConferencingProviderType.ZOOM.externalId());
        assertEquals("microsoft_teams", ConferencingProviderType.MICROSOFT_TEAMS.externalId());
    }
}
