package io.bunnycal.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingRedirectProperties;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderRequests;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceCheckoutTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock PlanService planService;
    @Mock TimeSource timeSource;
    @Mock PaymentAuditService auditService;
    @Mock PromotionService promotionService;
    @Mock TrialLifecycleService trialLifecycleService;
    @Mock PaymentProvider paymentProvider;

    private SubscriptionService service;
    private SubscriptionPlan plan;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(
                subscriptionRepository,
                userRepository,
                planService,
                timeSource,
                new BillingRedirectProperties(
                        "https://app.example.test/success",
                        "https://app.example.test/cancel",
                        "https://app.example.test/portal"),
                auditService,
                promotionService,
                trialLifecycleService,
                paymentProvider);
        plan = SubscriptionPlan.builder()
                .id(PLAN_ID)
                .code("pro_monthly")
                .name("Professional")
                .providerPriceId("prod_pro")
                .trialDays(14)
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(User.builder()
                .id(USER_ID)
                .email("customer@example.test")
                .name("Customer")
                .timezone("UTC")
                .build()));
        when(planService.requireDefaultPlan()).thenReturn(plan);
        // Lenient: the duplicate-purchase guard blocks some tests before checkout is created.
        org.mockito.Mockito.lenient().when(paymentProvider.createCheckoutSession(any())).thenReturn(
                new ProviderRequests.CheckoutSession("cs_1", "https://checkout.example.test/cs_1"));
    }

    @Test
    void checkoutDuringTrialRequestsImmediateBillingWithZeroProviderTrialDays() {
        Subscription trial = Subscription.builder()
                .userId(USER_ID)
                .planId(PLAN_ID)
                .status(SubscriptionStatus.TRIAL)
                .trialStart(NOW)
                .trialEnd(NOW.plusSeconds(7 * 24 * 60 * 60))
                .trialConsumed(true)
                .providerCustomerId("cus_1")
                .build();
        when(subscriptionRepository.findLiveByUserId(USER_ID)).thenReturn(Optional.of(trial));
        when(trialLifecycleService.expireIfElapsed(trial)).thenReturn(false);

        service.startCheckout(USER_ID);

        assertCheckoutExplicitlyDisablesProviderTrial();
    }

    @Test
    void checkoutAfterExpiredTrialRequestsImmediateBillingWithoutCreatingAnotherTrial() {
        when(subscriptionRepository.findLiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.existsByUserIdAndTrialConsumedTrue(USER_ID)).thenReturn(true);
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentProvider.createCustomer(any())).thenReturn(
                new ProviderRequests.CustomerRef("cus_returning"));

        service.startCheckout(USER_ID);

        assertCheckoutExplicitlyDisablesProviderTrial();
        verify(subscriptionRepository).existsByUserIdAndTrialConsumedTrue(USER_ID);
    }

    @Test
    void checkoutBlockedWhenProviderAlreadyHasLiveSubscriptionForCustomer() {
        Subscription incomplete = Subscription.builder()
                .userId(USER_ID)
                .planId(PLAN_ID)
                .status(SubscriptionStatus.INCOMPLETE)
                .trialConsumed(true)
                .providerCustomerId("cus_1")
                .build();
        when(subscriptionRepository.findLiveByUserId(USER_ID)).thenReturn(Optional.of(incomplete));
        when(trialLifecycleService.expireIfElapsed(incomplete)).thenReturn(false);
        // Provider reports an already-active subscription for this customer (a missed activation).
        when(paymentProvider.listSubscriptionsForCustomer("cus_1")).thenReturn(java.util.List.of(
                new io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot(
                        "sub_existing", "cus_1", USER_ID.toString(),
                        io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal.ACTIVE,
                        false, null, null, null)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.startCheckout(USER_ID))
                .isInstanceOf(io.bunnycal.common.exception.CustomException.class);

        // Never proceeded to create a provider checkout session.
        org.mockito.Mockito.verify(paymentProvider, org.mockito.Mockito.never())
                .createCheckoutSession(any());
    }

    private void assertCheckoutExplicitlyDisablesProviderTrial() {
        ArgumentCaptor<ProviderRequests.CheckoutSessionRequest> request =
                ArgumentCaptor.forClass(ProviderRequests.CheckoutSessionRequest.class);
        verify(paymentProvider).createCheckoutSession(request.capture());
        assertThat(request.getValue().trialDays()).isZero();
    }
}
