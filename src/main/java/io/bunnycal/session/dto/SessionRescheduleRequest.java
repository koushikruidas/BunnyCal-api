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
 *
 * <p>There is deliberately no "release the original time" option. Whether the vacated hour is
 * available to other event types is not decidable per-session: the recurring window reserves that
 * hour from its configuration alone, so releasing a session's hold cannot free a slot the rule
 * still covers. A control that works only when the origin happens to fall outside its own window
 * would be indistinguishable from a broken one. The vacated hour therefore always stays blocked,
 * which is the safe reading of a reschedule anyway. Reopening it means editing the window.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionRescheduleRequest(
        Instant startTime,
        boolean acknowledgeExternalConflicts) implements ForwardCompatibleRequest {
}
