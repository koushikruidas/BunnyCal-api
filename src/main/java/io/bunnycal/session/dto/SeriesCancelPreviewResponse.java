package io.bunnycal.session.dto;

import java.time.Instant;

/**
 * What a series cancel would affect, so the host confirms against real numbers
 * ("this will cancel 6 sessions and notify 41 guests") rather than discovering the
 * blast radius afterwards.
 */
public record SeriesCancelPreviewResponse(
        int sessionCount,
        int affectedGuestCount,
        Instant from) {}
