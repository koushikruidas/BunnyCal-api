package io.bunnycal.billing;

import io.bunnycal.testsupport.TestContainers;
import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.PlanService;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Milestone-2 verification: trial creation + single-trial guard, the one-live-subscription
 * invariant, the webhook state machine, and entitlement computation.
 *
 * <p>billing.enabled=true so the webhook handler and StripeProvider beans load; the dummy
 * Stripe key is never used over the network here (we drive the handler with synthetic
 * provider-neutral events).
 */
@SpringBootTest(classes = TestApplication.class)
@org.springframework.context.annotation.Import(
        io.bunnycal.testsupport.ProgrammableBillingProviderConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false",
        "billing.enabled=true",
        "billing.stripe.secret-key=sk_test_dummy",
        "billing.stripe.webhook-secret=whsec_dummy"
})
class SubscriptionCoreIT {



    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestContainers.registerProperties(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired SubscriptionStateService stateService;
    @Autowired PlanService planService;
    @Autowired WebhookEventHandler webhookHandler;
    @Autowired io.bunnycal.testsupport.ProgrammableBillingProvider fakeProvider;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        jdbc.execute("TRUNCATE TABLE subscriptions, payment_audit_logs CASCADE");
        jdbc.execute("TRUNCATE TABLE users CASCADE");
    }

    private User newUser() {
        return userRepository.save(User.builder()
                .email(UUID.randomUUID() + "@example.com")
                .name("Test User")
                .timezone("UTC")
                .build());
    }

    @Test
    void firstAccessStartsATrialAndUserIsEntitled() {
        User user = newUser();

        SubscriptionStateDto state = stateService.resolve(user.getId());

        assertThat(state.status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(state.entitled()).isTrue();
        assertThat(state.trialEnd()).isNotNull();
        Subscription sub = subscriptionRepository.findLiveByUserId(user.getId()).orElseThrow();
        assertThat(sub.isTrialConsumed()).isTrue();
        assertThat(Duration.between(sub.getTrialStart(), sub.getTrialEnd()).toDays())
                .isEqualTo(planService.requireDefaultPlan().getTrialDays());
    }

    @Test
    void ensureSubscriptionIsIdempotent() {
        User user = newUser();

        subscriptionService.ensureSubscription(user.getId());
        subscriptionService.ensureSubscription(user.getId());

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE user_id = ?", Long.class, user.getId());
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void cancelledUserDoesNotReceiveASecondTrial() {
        User user = newUser();
        Subscription trial = subscriptionService.ensureSubscription(user.getId()).orElseThrow();

        // Simulate the trial being cancelled (terminal state).
        trial.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(trial);

        // No live subscription, but trial already consumed -> no new trial.
        assertThat(subscriptionService.ensureSubscription(user.getId())).isEmpty();
        assertThat(stateService.resolve(user.getId()).status()).isNull();
    }

    @Test
    void resolvingAnElapsedTrialPersistsExpiredAndNoLongerReportsTrial() {
        User user = newUser();
        Subscription trial = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        trial.setTrialEnd(Instant.now().minusSeconds(1));
        subscriptionRepository.saveAndFlush(trial);

        SubscriptionStateDto state = stateService.resolve(user.getId());

        assertThat(state.status()).isNull();
        assertThat(state.entitled()).isFalse();
        assertThat(subscriptionRepository.findById(trial.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    void successfulCheckoutImmediatelyTransitionsTrialToActive() {
        User user = newUser();
        Subscription trial = subscriptionService.ensureSubscription(user.getId()).orElseThrow();

        // Reconcile-by-read: the handler reads current provider state for the subscription id on
        // the event, so register the ACTIVE snapshot the provider would return.
        fakeProvider.putSubscription(new io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot(
                "sub_upgrade", "cus_upgrade", user.getId().toString(),
                io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal.ACTIVE,
                false, null, null, null));

        webhookHandler.handle(new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID(),
                "subscription.active",
                io.bunnycal.payments.provider.BillingEventType.CHECKOUT_COMPLETED,
                "{}",
                ProviderWebhookEvent.Data.builder()
                        .providerSubscriptionId("sub_upgrade")
                        .providerCustomerId("cus_upgrade")
                        .userId(user.getId().toString())
                        .build()));

        Subscription upgraded = subscriptionRepository.findById(trial.getId()).orElseThrow();
        assertThat(upgraded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(upgraded.getTrialEnd()).isEqualTo(trial.getTrialEnd());
        assertThat(stateService.resolve(user.getId()).entitled()).isTrue();
    }

    @Test
    void invoicePaidWebhookActivatesSubscription() {
        User user = newUser();
        Subscription sub = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        sub.setProviderCustomerId("cus_123");
        sub.setProviderSubscriptionId("sub_123");
        subscriptionRepository.save(sub);

        // The handler reads the payment named by the event (invoice id doubles as payment id here).
        fakeProvider.putPayment(new io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot(
                "in_core1", io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot.PaymentStatus.SUCCEEDED,
                "sub_123", "cus_123", "in_core1", null, null, 99900, 0, 99900, "inr", null, null));

        ProviderWebhookEvent event = new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID(),
                "invoice.paid",
                io.bunnycal.payments.provider.BillingEventType.INVOICE_PAID,
                "{}",
                ProviderWebhookEvent.Data.builder()
                        .providerInvoiceId("in_core1")
                        .providerSubscriptionId("sub_123")
                        .providerCustomerId("cus_123")
                        .currency("inr")
                        .subtotalMinor(99900)
                        .totalMinor(99900)
                        .build());

        webhookHandler.handle(event);

        Subscription after = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(stateService.resolve(user.getId()).entitled()).isTrue();
    }

    @Test
    void invoicePaymentFailedMovesToPastDueWithGrace() {
        User user = newUser();
        Subscription sub = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        // A failed renewal must start ACTIVE for the past-due transition to be legal (grace begins
        // from ACTIVE). Provider now reports the subscription past_due; reconcile reads that.
        sub.setProviderSubscriptionId("sub_456");
        sub.activate();
        subscriptionRepository.save(sub);
        fakeProvider.putSubscription(new io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot(
                "sub_456", null, user.getId().toString(),
                io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal.PAST_DUE,
                false, null, null, null));

        webhookHandler.handle(new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID(),
                "invoice.payment_failed",
                io.bunnycal.payments.provider.BillingEventType.INVOICE_FAILED,
                "{}",
                ProviderWebhookEvent.Data.builder()
                        .providerSubscriptionId("sub_456")
                        .build()));

        Subscription after = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(after.getGraceUntil()).isNotNull();
        // Entitled during grace.
        assertThat(stateService.isEntitled(after)).isTrue();
    }

    @Test
    void staleQueryReturnsCustomerOnlyIncompleteRowSoDroppedWebhooksSelfHeal() {
        // The dropped-webhook incident: a checkout paid but the activation webhook was lost, so the
        // row is INCOMPLETE with a customer id but NO subscription id. It must still be a
        // reconciliation candidate (resolved by customer) — requiring a subscription id would
        // strand it forever.
        User user = newUser();
        Subscription stuck = subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planId(planService.requireDefaultPlan().getId())
                .status(SubscriptionStatus.INCOMPLETE)
                .providerSubscriptionId(null)
                .providerCustomerId("cus_dropped")
                .trialConsumed(true)
                .build());

        var candidates = subscriptionRepository.findStaleForReconciliation(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(candidates).extracting(Subscription::getId).contains(stuck.getId());
    }

    @Test
    void staleQueryExcludesRowsWithNeitherSubscriptionNorCustomerId() {
        User user = newUser();
        Subscription orphan = subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planId(planService.requireDefaultPlan().getId())
                .status(SubscriptionStatus.INCOMPLETE)
                .providerSubscriptionId(null)
                .providerCustomerId(null)
                .trialConsumed(true)
                .build());

        var candidates = subscriptionRepository.findStaleForReconciliation(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        // Nothing to attribute this row to at the provider — never a candidate (never match by email).
        assertThat(candidates).extracting(Subscription::getId).doesNotContain(orphan.getId());
    }
}
