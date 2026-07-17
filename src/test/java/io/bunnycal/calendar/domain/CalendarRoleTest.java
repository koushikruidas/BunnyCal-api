package io.bunnycal.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.calendar.client.ProviderCalendarInventoryEntry;
import org.junit.jupiter.api.Test;

class CalendarRoleTest {

    @Test
    void primaryFlagAlwaysWins() {
        assertThat(CalendarRole.classify(CalendarProviderType.GOOGLE,
                entry("en.indian#holiday@group.v.calendar.google.com", "Holidays", true)))
                .isEqualTo(CalendarRole.PRIMARY);
    }

    @Test
    void googleHolidayUsesStableProviderId() {
        assertThat(CalendarRole.classify(CalendarProviderType.GOOGLE,
                entry("en.indian#holiday@group.v.calendar.google.com", "Holidays in India", false)))
                .isEqualTo(CalendarRole.HOLIDAY);
        assertThat(CalendarRole.classify(CalendarProviderType.GOOGLE,
                entry("birthdays#contacts@group.v.calendar.google.com", "Birthdays", false)))
                .isEqualTo(CalendarRole.OTHER);
    }

    @Test
    void microsoftHolidayUsesSafeNameHeuristic() {
        assertThat(CalendarRole.classify(CalendarProviderType.MICROSOFT,
                entry("opaque-1", "India holidays", false))).isEqualTo(CalendarRole.HOLIDAY);
        assertThat(CalendarRole.classify(CalendarProviderType.MICROSOFT,
                entry("opaque-localized", "Jours fériés en Inde", false))).isEqualTo(CalendarRole.HOLIDAY);
        assertThat(CalendarRole.classify(CalendarProviderType.MICROSOFT,
                entry("opaque-2", "Birthdays", false))).isEqualTo(CalendarRole.OTHER);
        assertThat(CalendarRole.classify(CalendarProviderType.MICROSOFT,
                entry("opaque-3", "Family", false))).isEqualTo(CalendarRole.OTHER);
    }

    private static ProviderCalendarInventoryEntry entry(String id, String name, boolean primary) {
        return new ProviderCalendarInventoryEntry(id, name, primary, true, true, false);
    }
}
