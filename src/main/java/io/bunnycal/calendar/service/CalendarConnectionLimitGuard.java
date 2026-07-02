package io.bunnycal.calendar.service;

import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.LimitKey;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enforces the per-plan "maximum connected calendars" limit (Spec Ch2 §9: Free = 1, all other
 * tiers unlimited). Shared by the Google and Microsoft OAuth callback flows so the rule — and
 * its routing through {@link EntitlementService} — lives in exactly one place (no duplication).
 *
 * <p>Only a <b>net-new</b> connection is gated. Re-authenticating a provider the user is already
 * connected to is always allowed (it replaces the existing row; the connected count does not
 * grow), so a Free user can refresh their single calendar without being blocked.
 *
 * <p>Phase 2 prevents <em>creating</em> an over-limit condition. The downgrade "Over Limit"
 * state (pausing sync until the user picks which calendar to keep) is Phase 4 lifecycle work.
 */
@Component
@RequiredArgsConstructor
public class CalendarConnectionLimitGuard {

    private final CalendarConnectionRepository connectionRepository;
    private final EntitlementService entitlementService;

    /**
     * Requires that the user may add one more connected calendar.
     *
     * @param user             the connecting user
     * @param isNetNewProvider true when no live connection for this provider exists yet (i.e.
     *                         this connect would increase the connected-calendar count). When
     *                         false (re-auth of an existing provider), the check is skipped.
     * @throws io.bunnycal.common.exception.CustomException
     *     ({@link io.bunnycal.common.enums.ErrorCode#PLAN_LIMIT_REACHED}, HTTP 403) when adding
     *     a net-new calendar would exceed the plan's limit.
     */
    public void requireCanConnect(UUID userId, boolean isNetNewProvider) {
        if (!isNetNewProvider) {
            return;
        }
        long currentlyConnected = connectionRepository.countConnectedByUser(userId);
        entitlementService.requireWithinLimit(userId, LimitKey.CONNECTED_CALENDARS, currentlyConnected);
    }
}
