package io.bunnycal.session.dto;

import java.util.List;

public record SessionPageResponse(
        List<SessionSummaryResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
