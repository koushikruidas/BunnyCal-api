package io.bunnycal.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
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
 * Milestone-1 deliverable verification: a verified webhook is persisted and audited,
 * and a redelivered event with the same provider_event_id is processed exactly once.
 *
 * <p>Exercises {@link WebhookIngestionService} directly with provider-neutral
 * {@link ProviderWebhookEvent} objects, so it does not require Stripe credentials or
 * real signature verification (covered separately by StripeProvider unit behaviour).
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
        "scheduling.enabled=false"
})
class WebhookIngestionIT {

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
    @Autowired WebhookIngestionService ingestionService;
    @Autowired WebhookEventRepository webhookEventRepository;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE webhook_events, payment_audit_logs CASCADE");
    }

    @Test
    void persistsAndAuditsAVerifiedWebhook() {
        String eventId = "evt_" + UUID.randomUUID();
        ProviderWebhookEvent event = new ProviderWebhookEvent(
                eventId, "invoice.paid", "{\"id\":\"" + eventId + "\",\"type\":\"invoice.paid\"}");

        boolean fresh = ingestionService.ingest(event);

        assertThat(fresh).isTrue();
        var stored = webhookEventRepository.findByProviderAndProviderEventId("STRIPE", eventId);
        assertThat(stored).isPresent();
        assertThat(stored.get().getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(stored.get().getProcessedAt()).isNotNull();

        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM payment_audit_logs WHERE action = 'WEBHOOK_RECEIVED' AND entity_id = ?",
                Integer.class, stored.get().getId());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void redeliveredEventIsProcessedExactlyOnce() {
        String eventId = "evt_" + UUID.randomUUID();
        ProviderWebhookEvent event = new ProviderWebhookEvent(
                eventId, "customer.subscription.updated", "{\"id\":\"" + eventId + "\"}");

        boolean first = ingestionService.ingest(event);
        boolean second = ingestionService.ingest(event);

        assertThat(first).isTrue();
        assertThat(second).isFalse();

        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_events WHERE provider_event_id = ?", Long.class, eventId);
        assertThat(rows).isEqualTo(1L);

        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM payment_audit_logs WHERE action = 'WEBHOOK_RECEIVED'",
                Integer.class);
        assertThat(auditCount).isEqualTo(1);
    }
}
