package io.bunnycal.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventHandler;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Milestone-2 verification: trial creation + single-trial guard, the one-live-subscription
 * invariant, the webhook state machine, and entitlement computation.
 *
 * <p>billing.enabled=true so the webhook handler and StripeProvider beans load; the dummy
 * Stripe key is never used over the network here (we drive the handler with synthetic
 * provider-neutral events).
 */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers
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

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired SubscriptionStateService stateService;
    @Autowired WebhookEventHandler webhookHandler;

    @BeforeEach
    void setUp() {
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
    void invoicePaidWebhookActivatesSubscription() {
        User user = newUser();
        Subscription sub = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        sub.setProviderCustomerId("cus_123");
        sub.setProviderSubscriptionId("sub_123");
        subscriptionRepository.save(sub);

        ProviderWebhookEvent event = new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID(),
                "invoice.paid",
                "{\"data\":{\"object\":{\"id\":\"in_core1\",\"subscription\":\"sub_123\",\"customer\":\"cus_123\","
                        + "\"currency\":\"inr\",\"subtotal\":99900,\"amount_paid\":99900,\"total\":99900}}}");

        webhookHandler.handle(event);

        Subscription after = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(stateService.resolve(user.getId()).entitled()).isTrue();
    }

    @Test
    void invoicePaymentFailedMovesToPastDueWithGrace() {
        User user = newUser();
        Subscription sub = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        sub.setProviderSubscriptionId("sub_456");
        subscriptionRepository.save(sub);

        webhookHandler.handle(new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID(),
                "invoice.payment_failed",
                "{\"data\":{\"object\":{\"subscription\":\"sub_456\"}}}"));

        Subscription after = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(after.getGraceUntil()).isNotNull();
        // Entitled during grace.
        assertThat(stateService.isEntitled(after)).isTrue();
    }
}
