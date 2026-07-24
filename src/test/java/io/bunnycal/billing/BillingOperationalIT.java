package io.bunnycal.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.DunningScheduler;
import io.bunnycal.billing.service.PlanService;
import io.bunnycal.billing.service.TrialExpirationScheduler;
import io.bunnycal.billing.service.TrialReminderScheduler;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Milestone-6 verification: dunning expiry, trial-reminder window selection, and that
 * lifecycle changes emit billing outbox events (the email send itself is exercised by the
 * outbox worker + dispatcher, not asserted here).
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
class BillingOperationalIT {

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
    @Autowired PlanService planService;
    @Autowired TrialReminderScheduler trialReminderScheduler;
    @Autowired TrialExpirationScheduler trialExpirationScheduler;
    @Autowired DunningScheduler dunningScheduler;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE subscriptions, payment_audit_logs CASCADE");
        jdbc.execute("DELETE FROM outbox_events WHERE aggregate_type IN ('Subscription', 'Invoice')");
        jdbc.execute("TRUNCATE TABLE users CASCADE");
    }

    private User newUser() {
        return userRepository.save(User.builder()
                .email(UUID.randomUUID() + "@example.com").name("Test User").timezone("UTC").build());
    }

    private Subscription sub(SubscriptionStatus status, Instant trialEnd, Instant graceUntil) {
        return subscriptionRepository.save(Subscription.builder()
                .userId(newUser().getId())
                .planId(planService.requireDefaultPlan().getId())
                .status(status)
                .trialEnd(trialEnd)
                .graceUntil(graceUntil)
                .trialConsumed(status == SubscriptionStatus.TRIAL)
                .build());
    }

    private long billingEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ?", Long.class, eventType);
    }

    @Test
    void dunningExpiresPastDueBeyondGraceAndNotifies() {
        Subscription lapsed = sub(SubscriptionStatus.PAST_DUE, null, Instant.now().minus(1, ChronoUnit.HOURS));
        Subscription withinGrace = sub(SubscriptionStatus.PAST_DUE, null, Instant.now().plus(1, ChronoUnit.DAYS));

        dunningScheduler.expireLapsedSubscriptions();

        assertThat(subscriptionRepository.findById(lapsed.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(subscriptionRepository.findById(withinGrace.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(billingEvents("SUBSCRIPTION_EXPIRED")).isEqualTo(1);
    }

    @Test
    void trialReminderSelectsConfiguredWindows() {
        Instant now = Instant.now();
        sub(SubscriptionStatus.TRIAL, now.plus(7, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS), null); // ~7 days
        sub(SubscriptionStatus.TRIAL, now.plus(3, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS), null); // ~3 days
        sub(SubscriptionStatus.TRIAL, now.plus(20, ChronoUnit.DAYS), null);                          // out of window

        trialReminderScheduler.sendTrialReminders();

        // The 7-day and 3-day subs each produce one reminder; the 20-day one produces none.
        assertThat(billingEvents("TRIAL_ENDING")).isEqualTo(2);
    }

    @Test
    void trialExpirationSweepTransitionsOnlyElapsedTrials() {
        Instant now = Instant.now();
        Subscription elapsed = sub(
                SubscriptionStatus.TRIAL, now.minus(1, ChronoUnit.HOURS), null);
        Subscription active = sub(
                SubscriptionStatus.TRIAL, now.plus(1, ChronoUnit.DAYS), null);

        trialExpirationScheduler.expireElapsedTrials();

        assertThat(subscriptionRepository.findById(elapsed.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(subscriptionRepository.findById(active.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(billingEvents("SUBSCRIPTION_EXPIRED")).isEqualTo(1);
    }
}
