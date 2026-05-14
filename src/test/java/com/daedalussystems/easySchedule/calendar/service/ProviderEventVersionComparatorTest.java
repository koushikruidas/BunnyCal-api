package com.daedalussystems.easySchedule.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderEventVersionComparatorTest {

    private final ProviderEventVersionComparator comparator = new ProviderEventVersionComparator();

    @Test
    void compare_prefersHigherSequence() {
        var incoming = new ProviderEventVersionComparator.VersionVector(11L, null, null, null);
        var persisted = new ProviderEventVersionComparator.VersionVector(10L, null, null, null);
        assertEquals(ProviderEventVersionComparator.ComparisonResult.NEWER, comparator.compare(incoming, persisted));
    }

    @Test
    void compare_prefersNewerUpdatedAtWhenSequenceMissing() {
        var incoming = new ProviderEventVersionComparator.VersionVector(
                null, Instant.parse("2026-05-14T00:00:10Z"), null, null);
        var persisted = new ProviderEventVersionComparator.VersionVector(
                null, Instant.parse("2026-05-14T00:00:01Z"), null, null);
        assertEquals(ProviderEventVersionComparator.ComparisonResult.NEWER, comparator.compare(incoming, persisted));
    }

    @Test
    void compare_detectsAmbiguousEtagChange() {
        var incoming = new ProviderEventVersionComparator.VersionVector(null, null, "etag-2", null);
        var persisted = new ProviderEventVersionComparator.VersionVector(null, null, "etag-1", null);
        assertEquals(ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT, comparator.compare(incoming, persisted));
    }

    @Test
    void compare_rejectsOlderOrEqualPayload() {
        var incoming = new ProviderEventVersionComparator.VersionVector(5L, null, null, "h1");
        var persisted = new ProviderEventVersionComparator.VersionVector(5L, null, null, "h1");
        assertEquals(ProviderEventVersionComparator.ComparisonResult.OLDER_OR_EQUAL, comparator.compare(incoming, persisted));
    }
}
