package io.bunnycal.session.dto;

import java.time.Instant;

public record HoldActiveResponse(Instant expiresAt, long remainingSeconds) {

    public static HoldActiveResponse of(Instant expiresAt, Instant now) {
        long remaining = Math.max(0L, expiresAt.getEpochSecond() - now.getEpochSecond());
        return new HoldActiveResponse(expiresAt, remaining);
    }
}
