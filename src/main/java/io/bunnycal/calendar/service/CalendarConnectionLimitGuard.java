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
 * <p>Only a <b>net-new account</b> is gated. Re-authenticating (or reconnecting) an external
 * account the user already holds a row for is always allowed — it updates that row, so the
 * connected count does not grow — and a Free user can refresh their single calendar without
 * being blocked. Since V118_0 a user may hold several accounts per provider, so "net-new" is
 * decided by the external account identity, not by the provider.
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
     * @param userId          the connecting user
     * @param isNetNewAccount true when the user holds no row for this external account yet (i.e.
     *                        this connect would increase the connected-calendar count). When false
     *                        (re-auth or reconnect of an account already on file), the check is
     *                        skipped.
     * @throws io.bunnycal.common.exception.CustomException
     *     ({@link io.bunnycal.common.enums.ErrorCode#PLAN_LIMIT_REACHED}, HTTP 403) when adding
     *     a net-new calendar would exceed the plan's limit.
     */
    public void requireCanConnect(UUID userId, boolean isNetNewAccount) {
        if (!isNetNewAccount) {
            return;
        }
        long currentlyConnected = connectionRepository.countConnectedByUser(userId);
        entitlementService.requireWithinLimit(userId, LimitKey.CONNECTED_CALENDARS, currentlyConnected);
    }
}
