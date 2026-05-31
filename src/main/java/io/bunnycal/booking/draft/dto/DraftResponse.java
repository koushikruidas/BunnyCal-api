package io.bunnycal.booking.draft.dto;

import io.bunnycal.booking.draft.domain.DraftLifecycleState;
import java.time.Instant;

public record DraftResponse(
        String slug,
        String publicUrl,
        String email,
        String displayName,
        String timezone,
        String eventName,
        Integer durationMinutes,
        DraftLifecycleState state,
        Instant expiresAt
) {
}
