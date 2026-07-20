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
        boolean acknowledgeExternalConflicts,
        Boolean keepOriginalTimeBlocked) implements ForwardCompatibleRequest {

    /**
     * Whether the vacated hour stays blocked for the host's other event types.
     *
     * <p>Boxed rather than primitive so an omitted field is distinguishable from an explicit
     * {@code false}: a primitive would default to {@code false} and silently reopen the host's
     * calendar for every client that has not been updated. Absent means blocked.
     *
     * <p>This never affects whether the moved occurrence itself is regenerated — it is not.
     */
    public boolean keepOriginalTimeBlockedOrDefault() {
        return keepOriginalTimeBlocked == null || keepOriginalTimeBlocked;
    }
}
