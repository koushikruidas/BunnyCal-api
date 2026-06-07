package io.bunnycal.session.dto;

import java.util.List;

public record SessionRegistrationPageResponse(
        List<SessionRegistrationResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
