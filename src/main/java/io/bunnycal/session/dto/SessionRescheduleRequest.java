package io.bunnycal.session.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.bunnycal.common.api.ForwardCompatibleRequest;
import java.time.Instant;

/**
 * Host-side group session reschedule.
 *
 * <p>Separate from {@code PublicRescheduleRequest} because
 * {@code acknowledgeExternalConflicts} is a host-only capability: overriding busy time on your
 * own connected calendar is a judgement only the host can make, and the guest-facing DTO should
 * not carry a field guests must never be able to set.
 *
 * <p>Duration is deliberately absent — a reschedule moves one occurrence and preserves its
 * length, so the new end time is derived from the session rather than supplied by the client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionRescheduleRequest(
        Instant startTime,
        boolean acknowledgeExternalConflicts) implements ForwardCompatibleRequest {
}
