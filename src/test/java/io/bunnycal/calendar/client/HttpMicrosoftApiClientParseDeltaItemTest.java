package io.bunnycal.calendar.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tombstone parsing tests for the Microsoft Graph delta feed.
 *
 * <p>Microsoft's {@code @removed} payload contains only {@code id} and the
 * removal reason — no {@code lastModifiedDateTime}, {@code changeKey}, or
 * {@code sequence}. Without synthesis, those nulls feed the version comparator
 * to {@code AMBIGUOUS_NEWER_HINT} and (under {@code accept-ambiguous=false})
 * the projection writer drops the tombstone on the floor, leaving the
 * {@code calendar_events} row stuck ACTIVE forever.
 */
class HttpMicrosoftApiClientParseDeltaItemTest {

    @Test
    void removedTombstone_synthesizesUpdatedAtSoComparatorCanReturnNewer() {
        Instant before = Instant.now();
        var observation = HttpMicrosoftApiClient.parseDeltaItem(Map.of(
                "id", "AQ...AAPVPpoAAAA=",
                "@removed", Map.of("reason", "deleted")));
        Instant after = Instant.now();

        assertThat(observation.externalEventId()).isEqualTo("AQ...AAPVPpoAAAA=");
        assertThat(observation.deleted()).isTrue();
        assertThat(observation.cancelled()).isTrue();
        // The synthesized timestamp must be non-null and roughly equal to "now",
        // so the comparator hits the providerUpdatedAt branch and returns NEWER.
        assertThat(observation.providerUpdatedAt()).isNotNull();
        assertThat(observation.providerUpdatedAt()).isBetween(
                before.minus(Duration.ofSeconds(1)),
                after.plus(Duration.ofSeconds(1)));
    }

    @Test
    void activeEvent_preservesGraphLastModifiedDateTime() {
        Instant graphProvided = Instant.parse("2026-05-28T06:55:50Z");
        var observation = HttpMicrosoftApiClient.parseDeltaItem(Map.of(
                "id", "AQ...ACTIVE",
                "start", Map.of("dateTime", "2026-05-28T10:00:00.0000000", "timeZone", "UTC"),
                "end",   Map.of("dateTime", "2026-05-28T10:30:00.0000000", "timeZone", "UTC"),
                "isCancelled", false,
                "lastModifiedDateTime", graphProvided.toString(),
                "changeKey", "real-changekey",
                "subject", "Active event"));

        assertThat(observation.deleted()).isFalse();
        assertThat(observation.cancelled()).isFalse();
        // Active event must use Graph's timestamp verbatim — no synthesis on this path.
        assertThat(observation.providerUpdatedAt()).isEqualTo(graphProvided);
        assertThat(observation.providerEtag()).isEqualTo("real-changekey");
    }

    @Test
    void isCancelledTrue_withoutRemovedMarker_isNotTreatedAsTombstoneByDeletedFlag() {
        // isCancelled=true but no @removed: this is a "soft-cancel" event that Graph still
        // returns with full metadata. Our mapper should set cancelled=true, deleted=false,
        // and preserve the provided timestamps (no synthesis needed because they exist).
        Instant graphProvided = Instant.parse("2026-05-28T07:00:00Z");
        var observation = HttpMicrosoftApiClient.parseDeltaItem(Map.of(
                "id", "AQ...SOFTCANCEL",
                "start", Map.of("dateTime", "2026-05-28T10:00:00.0000000", "timeZone", "UTC"),
                "end",   Map.of("dateTime", "2026-05-28T10:30:00.0000000", "timeZone", "UTC"),
                "isCancelled", true,
                "lastModifiedDateTime", graphProvided.toString(),
                "changeKey", "real-changekey",
                "subject", "Soft cancelled"));

        assertThat(observation.deleted()).isFalse();
        assertThat(observation.cancelled()).isTrue();
        assertThat(observation.providerUpdatedAt()).isEqualTo(graphProvided);
    }
}
