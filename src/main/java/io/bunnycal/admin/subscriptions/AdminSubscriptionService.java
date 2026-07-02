package io.bunnycal.admin.subscriptions;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.subscriptions.dto.AdminSubscriptionDto;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.PlanService;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-side subscription lifecycle operations, addressed by subscription id (admin context),
 * as opposed to {@link io.bunnycal.billing.service.SubscriptionService} which is user-scoped
 * and drives customer self-service. State transitions mutate the {@link Subscription} entity
 * directly — the same mechanism webhooks use — and every change is recorded to
 * {@code admin_audit_logs} with before/after snapshots.
 *
 * <p>Entitlement reads reuse {@link SubscriptionStateService#isEntitled}, the single
 * authority for "is this subscription currently entitled?".
 */
@Service
public class AdminSubscriptionService {

    private static final String TARGET_TYPE = "SUBSCRIPTION";
    /** "Lifetime" is modeled as ACTIVE with a far-future period end (no provider renewal). */
    private static final Instant LIFETIME_END = Instant.parse("2999-12-31T23:59:59Z");

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateService stateService;
    private final PlanService planService;
    private final AdminAuditService auditService;
    private final UserRepository userRepository;
    private final TimeSource timeSource;
    private final BillingProperties billingProperties;

    public AdminSubscriptionService(SubscriptionRepository subscriptionRepository,
                                    SubscriptionStateService stateService,
                                    PlanService planService,
                                    AdminAuditService auditService,
                                    UserRepository userRepository,
                                    TimeSource timeSource,
                                    BillingProperties billingProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.stateService = stateService;
        this.planService = planService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.timeSource = timeSource;
        this.billingProperties = billingProperties;
    }

    @Transactional(readOnly = true)
    public AdminSubscriptionDto get(UUID subscriptionId) {
        return toDto(require(subscriptionId));
    }

    /**
     * Re-reads the local subscription. A placeholder for a true provider fetch: the local row
     * is the mirror of record today, so "refresh"/"sync" returns the current persisted state.
     * Kept as an explicit action so the UI affordance and audit entry exist for when a real
     * provider-side fetch is wired.
     */
    @Transactional
    public AdminSubscriptionDto refresh(UUID adminId, UUID subscriptionId) {
        Subscription sub = require(subscriptionId);
        audit(adminId, "SUBSCRIPTION_REFRESH", sub.getId(), null, toDto(sub), toDto(sub));
        return toDto(sub);
    }

    @Transactional
    public AdminSubscriptionDto cancel(UUID adminId, UUID subscriptionId, boolean atPeriodEnd, String reason) {
        Subscription sub = require(subscriptionId);
        AdminSubscriptionDto before = toDto(sub);
        if (atPeriodEnd) {
            sub.setCancelAtPeriodEnd(true);
        } else {
            sub.setStatus(SubscriptionStatus.CANCELLED);
            sub.setCanceledAt(timeSource.now());
        }
        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, atPeriodEnd ? "SUBSCRIPTION_CANCEL_AT_PERIOD_END" : "SUBSCRIPTION_CANCEL",
                saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    @Transactional
    public AdminSubscriptionDto resume(UUID adminId, UUID subscriptionId, String reason) {
        Subscription sub = require(subscriptionId);
        if (sub.getStatus().isTerminal()) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot resume a terminal subscription; grant a new one instead.");
        }
        AdminSubscriptionDto before = toDto(sub);
        sub.setCancelAtPeriodEnd(false);
        sub.setCanceledAt(null);
        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, "SUBSCRIPTION_RESUME", saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    @Transactional
    public AdminSubscriptionDto expire(UUID adminId, UUID subscriptionId, String reason) {
        Subscription sub = require(subscriptionId);
        AdminSubscriptionDto before = toDto(sub);
        sub.setStatus(SubscriptionStatus.EXPIRED);
        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, "SUBSCRIPTION_EXPIRE", saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    @Transactional
    public AdminSubscriptionDto extendTrial(UUID adminId, UUID subscriptionId, int days, String reason) {
        if (days <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "days must be > 0.");
        }
        Subscription sub = require(subscriptionId);
        AdminSubscriptionDto before = toDto(sub);
        Instant base = sub.getTrialEnd() != null && sub.getTrialEnd().isAfter(timeSource.now())
                ? sub.getTrialEnd()
                : timeSource.now();
        Instant newEnd = base.plus(days, ChronoUnit.DAYS);
        sub.setStatus(SubscriptionStatus.TRIAL);
        sub.setTrialEnd(newEnd);
        sub.setCurrentPeriodEnd(newEnd);
        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, "SUBSCRIPTION_EXTEND_TRIAL", saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    /** Grants the user an active Pro subscription, creating a live row if none exists. */
    @Transactional
    public AdminSubscriptionDto grantPro(UUID adminId, UUID userId, String reason) {
        return grant(adminId, userId, reason, false, "SUBSCRIPTION_GRANT_PRO");
    }

    /** Puts the user into a fresh trial window, creating a live row if none exists. */
    @Transactional
    public AdminSubscriptionDto grantTrial(UUID adminId, UUID userId, Integer days, String reason) {
        int trialDays = days == null ? defaultTrialDays() : days;
        if (trialDays <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "days must be > 0.");
        }

        Subscription sub = subscriptionRepository.findLiveByUserId(userId).orElse(null);
        AdminSubscriptionDto before = sub == null ? null : toDto(sub);
        SubscriptionPlan plan = planService.requireDefaultPlan();
        Instant now = timeSource.now();
        Instant trialEnd = now.plus(trialDays, ChronoUnit.DAYS);

        if (sub == null) {
            sub = Subscription.builder()
                    .userId(userId)
                    .planId(plan.getId())
                    .build();
        } else {
            sub.setPlanId(plan.getId());
            sub.setCanceledAt(null);
            sub.setGraceUntil(null);
        }

        sub.setStatus(SubscriptionStatus.TRIAL);
        sub.setTrialStart(now);
        sub.setTrialEnd(trialEnd);
        sub.setTrialConsumed(true);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(trialEnd);
        sub.setCancelAtPeriodEnd(false);

        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, "SUBSCRIPTION_GRANT_TRIAL", saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    /** Grants a lifetime subscription (ACTIVE, far-future period end, no provider renewal). */
    @Transactional
    public AdminSubscriptionDto grantLifetime(UUID adminId, UUID userId, String reason) {
        return grant(adminId, userId, reason, true, "SUBSCRIPTION_GRANT_LIFETIME");
    }

    /** Removes paid/trial access and prevents lazy trial recreation, leaving the user FREE. */
    @Transactional
    public AdminSubscriptionDto setFree(UUID adminId, UUID userId, String reason) {
        Subscription sub = subscriptionRepository.findLiveByUserId(userId).orElse(null);
        AdminSubscriptionDto before = sub == null ? null : toDto(sub);
        if (sub == null) {
            SubscriptionPlan plan = planService.requireDefaultPlan();
            sub = Subscription.builder()
                    .userId(userId)
                    .planId(plan.getId())
                    .status(SubscriptionStatus.EXPIRED)
                    .trialConsumed(true)
                    .build();
            before = null;
        }
        sub.setStatus(SubscriptionStatus.EXPIRED);
        sub.setCancelAtPeriodEnd(false);
        sub.setCanceledAt(timeSource.now());
        sub.setGraceUntil(null);
        sub.setTrialConsumed(true);
        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, "SUBSCRIPTION_SET_FREE", saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    /** Backward-compatible name for existing callers. */
    @Transactional
    public AdminSubscriptionDto removePro(UUID adminId, UUID userId, String reason) {
        return setFree(adminId, userId, reason);
    }

    private AdminSubscriptionDto grant(UUID adminId, UUID userId, String reason,
                                       boolean lifetime, String action) {
        Subscription sub = subscriptionRepository.findLiveByUserId(userId).orElse(null);
        AdminSubscriptionDto before = sub == null ? null : toDto(sub);
        Instant now = timeSource.now();
        if (sub == null) {
            SubscriptionPlan plan = planService.requireDefaultPlan();
            sub = Subscription.builder()
                    .userId(userId)
                    .planId(plan.getId())
                    .status(SubscriptionStatus.ACTIVE)
                    .trialConsumed(true)
                    .currentPeriodStart(now)
                    .currentPeriodEnd(lifetime ? LIFETIME_END : null)
                    .build();
        } else {
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setTrialConsumed(true);
            sub.setCancelAtPeriodEnd(false);
            sub.setCanceledAt(null);
            sub.setGraceUntil(null);
            if (lifetime) {
                sub.setCurrentPeriodEnd(LIFETIME_END);
            }
        }
        Subscription saved = subscriptionRepository.save(sub);
        audit(adminId, action, saved.getId(), reason, before, toDto(saved));
        return toDto(saved);
    }

    private Subscription require(UUID id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }

    private AdminSubscriptionDto toDto(Subscription s) {
        return AdminSubscriptionDto.from(s, stateService.isEntitled(s));
    }

    private void audit(UUID adminId, String action, UUID subId, String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(u -> u.getEmail()).orElse(null);
        auditService.record(adminId, email, action, TARGET_TYPE, subId, reason, before, after);
    }

    private int defaultTrialDays() {
        SubscriptionPlan plan = planService.requireDefaultPlan();
        if (plan.getTrialDays() > 0) {
            return plan.getTrialDays();
        }
        if (billingProperties.trialDays() > 0) {
            return billingProperties.trialDays();
        }
        return 14;
    }
}
