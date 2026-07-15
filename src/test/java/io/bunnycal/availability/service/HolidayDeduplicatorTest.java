package io.bunnycal.availability.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HolidayDeduplicatorTest {

    @Test
    void sameOccasionOnNeighbouringDatesKeepsOnlyTheEarliestDate() {
        var result = HolidayDeduplicator.dedupe(List.of(
                holiday("Rath Yatra", "2026-07-16"),
                holiday("Rath Yatra", "2026-07-17")));

        assertThat(result).containsExactly(holiday("Rath Yatra", "2026-07-16"));
    }

    @Test
    void trailingRegionalQualifierStillDeduplicates() {
        var result = HolidayDeduplicator.dedupe(List.of(
                holiday("Janmashtami (Smarta)", "2026-08-15"),
                holiday("Janmashtami", "2026-08-16")));

        assertThat(result).containsExactly(holiday("Janmashtami", "2026-08-15"));
    }

    @Test
    void genericTitleLosesToDescriptiveTitleOnSameDate() {
        var result = HolidayDeduplicator.dedupe(List.of(
                holiday("Holiday", "2026-08-15"),
                holiday("Independence Day", "2026-08-15")));

        assertThat(result).containsExactly(holiday("Independence Day", "2026-08-15"));
    }

    @Test
    void slashCombinedTitleRemainsDistinctAsAgreed() {
        var result = HolidayDeduplicator.dedupe(List.of(
                holiday("Rath Yatra", "2026-07-16"),
                holiday("Rath Yatra / Raksha Bandhan", "2026-07-17")));

        assertThat(result).hasSize(2);
    }

    private static HolidayDeduplicator.Holiday holiday(String title, String date) {
        return new HolidayDeduplicator.Holiday(title, LocalDate.parse(date));
    }
}
