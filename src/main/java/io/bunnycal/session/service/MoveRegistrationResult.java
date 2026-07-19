package io.bunnycal.session.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of moving a registration between sessions (guest self-reschedule).
 *
 * <p>Carries both session ids because the caller needs the new one to build the
 * guest's updated view and the old one to invalidate anything keyed to it.
 */
public record MoveRegistrationResult(
        UUID sourceSessionId,
        UUID targetSessionId,
        UUID registrationId,
        Instant startTime,
        Instant endTime) {}
