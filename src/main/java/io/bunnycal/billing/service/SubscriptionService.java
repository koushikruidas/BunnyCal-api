package io.bunnycal.billing.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderRequests.CancelSubscriptionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CreateCustomerRequest;
import io.bunnycal.payments.provider.ProviderRequests.CustomerRef;
import io.bunnycal.payments.provider.ProviderRequests.PortalSession;
import io.bunnycal.payments.provider.ProviderRequests.PortalSessionRequest;
import io.bunnycal.payments.config.BillingRedirectProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core subscription lifecycle service.
 *
 * <p>Trial creation is provider-independent and idempotent (lazy: a TRIAL subscription
 * is created on first access for a user who has none and has never consumed a trial).
 * Checkout/portal/cancel require the {@link PaymentProvider}, which is only present when
 * {@code billing.enabled=true}; those methods fail with BILLING_DISABLED otherwise.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final String ENTITY = "Subscription";

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PlanService planService;
    private final TimeSource timeSource;
    private final BillingProperties billingProperties;
    private final BillingRedirectProperties redirectProperties;
    private final PaymentAuditService auditService;
    private final PromotionService promotionService;
    @Nullable
    private final PaymentProvider paymentProvider;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               UserRepository userRepository,
                               PlanService planService,
                               TimeSource timeSource,
                               BillingProperties billingProperties,
                               BillingRedirectProperties redirectProperties,
                               PaymentAuditService auditService,
                               PromotionService promotionService,
                               @Autowired(required = false) @Nullable PaymentProvider paymentProvider) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.planService = planService;
        this.timeSource = timeSource;
        this.billingProperties = billingProperties;
        this.redirectProperties = redirectProperties;
        this.auditService = auditService;
        this.promotionService = promotionService;
        this.paymentProvider = paymentProvider;
    }

    /** The user's current live subscription, or empty if none. */
    @Transactional(readOnly = true)
    public java.util.Optional<Subscription> findLive(UUID userId) {
        return subscriptionRepository.findLiveByUserId(userId);
    }

    /**
     * Returns the user's live subscription, lazily creating a TRIAL one if the user has
     * none and has never consumed a trial. Idempotent: concurrent callers converge on a
     * single row (guarded by the partial unique index; a race surfaces as a retryable
     * conflict). A user who already consumed a trial but has no live subscription gets
     * no new trial — they must subscribe.
     */
    @Transactional
    public java.util.Optional<Subscription> ensureSubscription(UUID userId) {
        java.util.Optional<Subscription> existing = subscriptionRepository.findLiveByUserId(userId);
        if (existing.isPresent()) {
            return existing;
        }
        if (subscriptionRepository.existsByUserIdAndTrialConsumedTrue(userId)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(startTrial(userId));
    }

    private Subscription startTrial(UUID userId) {
        SubscriptionPlan plan = planService.requireDefaultPlan();
        Instant now = timeSource.now();
        int trialDays = plan.getTrialDays() > 0 ? plan.getTrialDays() : billingProperties.trialDays();
        Instant trialEnd = now.plus(trialDays, ChronoUnit.DAYS);

        Subscription subscription = Subscription.builder()
                .userId(userId)
                .planId(plan.getId())
                .status(SubscriptionStatus.TRIAL)
                .trialStart(now)
                .trialEnd(trialEnd)
                .trialConsumed(true)
                .currentPeriodStart(now)
                .currentPeriodEnd(trialEnd)
                .build();

        Subscription saved;
        try {
            saved = subscriptionRepository.saveAndFlush(subscription);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Concurrent first-access created the live row first; return it.
            return subscriptionRepository.findLiveByUserId(userId)
                    .orElseThrow(() -> race);
        }
        auditService.record(PaymentAuditService.ACTOR_SYSTEM, ENTITY, saved.getId(),
                "TRIAL_STARTED", null, Map.of("trialEnd", trialEnd.toString(), "planId", plan.getId().toString()));
        log.info("billing.trial_started userId={} subscriptionId={} trialEnd={}", userId, saved.getId(), trialEnd);
        return saved;
    }

    /** Creates a hosted Checkout session for the default plan and returns the redirect URL. */
    @Transactional
    public CheckoutSession startCheckout(UUID userId) {
        return startCheckout(userId, null, null);
    }

    /**
     * Creates a hosted Checkout session, optionally applying a promo code. The promo is
     * validated and redeemed here so the actual provider charge is discounted (via the
     * coupon's provider id). A code with no linked provider coupon is honored in our
     * records but cannot discount the provider charge, so it is rejected to avoid a
     * mismatch between what the user sees and what they are charged.
     */
    @Transactional
    public CheckoutSession startCheckout(UUID userId, String promoCode) {
        return startCheckout(userId, null, promoCode);
    }

    /**
     * Creates checkout for an explicitly selected catalog plan. A null plan id preserves the
     * default-plan fallback for existing clients and trial upgrade prompts.
     */
    @Transactional
    public CheckoutSession startCheckout(UUID userId, UUID planId, String promoCode) {
        PaymentProvider provider = requireProvider();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));
        SubscriptionPlan plan = planId == null
                ? planService.requireDefaultPlan()
                : planService.requirePurchasablePlan(planId);
        if (plan.getProviderPriceId() == null || plan.getProviderPriceId().isBlank()) {
            throw new CustomException(ErrorCode.BILLING_PROVIDER_ERROR,
                    "Plan is not linked to a provider price.");
        }

        Subscription subscription = ensureSubscription(userId)
                .orElseGet(() -> reopenForCheckout(userId, plan));
        if (!plan.getId().equals(subscription.getPlanId())) {
            subscription.setPlanId(plan.getId());
            subscriptionRepository.save(subscription);
        }

        String customerId = subscription.getProviderCustomerId();
        if (customerId == null) {
            CustomerRef ref = provider.createCustomer(
                    new CreateCustomerRequest(userId, user.getEmail(), user.getName()));
            customerId = ref.providerCustomerId();
            subscription.setProviderCustomerId(customerId);
            subscriptionRepository.save(subscription);
        }

        String providerCouponId = null;
        if (promoCode != null && !promoCode.isBlank()) {
            PromotionService.ResolvedPromo resolved = promotionService.validate(promoCode, plan);
            providerCouponId = resolved.coupon().getProviderCouponId();
            if (providerCouponId == null || providerCouponId.isBlank()) {
                throw new CustomException(ErrorCode.PROMO_CODE_INVALID,
                        "This promo code is not available for checkout.");
            }
            if (!promotionService.redeem(resolved)) {
                throw new CustomException(ErrorCode.PROMO_CODE_EXHAUSTED);
            }
        }

        // Only offer trial days at checkout if the user has not already consumed a trial.
        Integer trialDays = subscription.isTrialConsumed() ? null : plan.getTrialDays();

        return provider.createCheckoutSession(new CheckoutSessionRequest(
                userId,
                customerId,
                plan.getProviderPriceId(),
                trialDays,
                redirectProperties.successUrl(),
                redirectProperties.cancelUrl(),
                providerCouponId));
    }

    private Subscription reopenForCheckout(UUID userId, SubscriptionPlan plan) {
        // User previously consumed a trial and has no live subscription: create an
        // INCOMPLETE row that the checkout webhook will activate.
        Subscription subscription = Subscription.builder()
                .userId(userId)
                .planId(plan.getId())
                .status(SubscriptionStatus.INCOMPLETE)
                .trialConsumed(true)
                .build();
        return subscriptionRepository.save(subscription);
    }

    /** Creates a hosted Customer Portal session and returns the redirect URL. */
    @Transactional(readOnly = true)
    public PortalSession openPortal(UUID userId) {
        PaymentProvider provider = requireProvider();
        Subscription subscription = subscriptionRepository.findLiveByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (subscription.getProviderCustomerId() == null) {
            throw new CustomException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                    "No billing customer exists yet. Start a checkout first.");
        }
        return provider.createPortalSession(new PortalSessionRequest(
                subscription.getProviderCustomerId(), redirectProperties.portalReturnUrl()));
    }

    /** Cancels the user's subscription (immediately or at period end). */
    @Transactional
    public Subscription cancel(UUID userId, boolean atPeriodEnd) {
        return cancel(userId, atPeriodEnd, null);
    }

    /**
     * Cancels the user's subscription, optionally recording a cancellation reason for
     * churn reporting. The reason is captured in the audit trail only — it does not
     * affect the cancellation itself.
     */
    @Transactional
    public Subscription cancel(UUID userId, boolean atPeriodEnd, String reason) {
        PaymentProvider provider = requireProvider();
        Subscription subscription = subscriptionRepository.findLiveByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        if (subscription.getProviderSubscriptionId() != null) {
            provider.cancelSubscription(new CancelSubscriptionRequest(
                    subscription.getProviderSubscriptionId(), atPeriodEnd));
        }

        SubscriptionStatus before = subscription.getStatus();
        if (atPeriodEnd) {
            subscription.setCancelAtPeriodEnd(true);
        } else {
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            subscription.setCanceledAt(timeSource.now());
        }
        Subscription saved = subscriptionRepository.save(subscription);

        java.util.Map<String, Object> after = new java.util.HashMap<>();
        after.put("status", saved.getStatus().name());
        after.put("cancelAtPeriodEnd", saved.isCancelAtPeriodEnd());
        if (reason != null && !reason.isBlank()) {
            after.put("reason", reason);
        }
        auditService.record(PaymentAuditService.userActor(userId), ENTITY, saved.getId(),
                atPeriodEnd ? "CANCEL_AT_PERIOD_END" : "CANCEL_IMMEDIATE",
                Map.of("status", before.name()), after);
        return saved;
    }

    private PaymentProvider requireProvider() {
        if (paymentProvider == null) {
            throw new CustomException(ErrorCode.BILLING_DISABLED);
        }
        return paymentProvider;
    }
}
