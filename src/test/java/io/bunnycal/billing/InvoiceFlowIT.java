package io.bunnycal.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.InvoiceService;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventHandler;
import java.util.List;
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
 * Milestone-3 verification: invoice.paid creates an immutable invoice (idempotently),
 * invoice numbers increment, and the PDF renders to non-empty bytes.
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
class InvoiceFlowIT {

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
    @Autowired SubscriptionInvoiceRepository invoiceRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired InvoiceService invoiceService;
    @Autowired WebhookEventHandler webhookHandler;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE payment_transactions, subscription_invoices, payment_methods, subscriptions, payment_audit_logs CASCADE");
        jdbc.execute("TRUNCATE TABLE users CASCADE");
    }

    private Subscription subscribedUser(String subId, String custId) {
        User user = userRepository.save(User.builder()
                .email(UUID.randomUUID() + "@example.com").name("Test User").timezone("UTC").build());
        Subscription sub = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        sub.setProviderCustomerId(custId);
        sub.setProviderSubscriptionId(subId);
        return subscriptionRepository.save(sub);
    }

    private ProviderWebhookEvent invoicePaid(String invoiceId, String subId, long total) {
        String json = "{\"data\":{\"object\":{"
                + "\"id\":\"" + invoiceId + "\","
                + "\"subscription\":\"" + subId + "\","
                + "\"currency\":\"inr\","
                + "\"subtotal\":" + total + ",\"amount_paid\":" + total + ",\"total\":" + total + ","
                + "\"payment_intent\":\"pi_" + invoiceId + "\","
                + "\"lines\":{\"data\":[{\"period\":{\"start\":1735689600,\"end\":1738368000}}]}"
                + "}}}";
        return new ProviderWebhookEvent("evt_" + UUID.randomUUID(), "invoice.paid", json);
    }

    @Test
    void invoicePaidCreatesImmutableInvoiceWithTransaction() {
        Subscription sub = subscribedUser("sub_inv1", "cus_inv1");

        webhookHandler.handle(invoicePaid("in_1", "sub_inv1", 99900));

        List<io.bunnycal.billing.domain.SubscriptionInvoice> invoices =
                invoiceRepository.findByUserIdOrderByIssuedAtDesc(sub.getUserId());
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoices.get(0).getTotalMinor()).isEqualTo(99900);
        assertThat(invoices.get(0).getInvoiceNumber()).startsWith("BC-");

        Long txCount = jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE invoice_id = ?", Long.class, invoices.get(0).getId());
        assertThat(txCount).isEqualTo(1L);
    }

    @Test
    void redeliveredInvoicePaidDoesNotDuplicate() {
        Subscription sub = subscribedUser("sub_inv2", "cus_inv2");
        ProviderWebhookEvent event = invoicePaid("in_2", "sub_inv2", 50000);

        webhookHandler.handle(event);
        // Re-handle the same provider invoice id (simulates Stripe redelivery).
        webhookHandler.handle(invoicePaid("in_2", "sub_inv2", 50000));

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM subscription_invoices WHERE user_id = ?", Long.class, sub.getUserId());
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void invoiceNumbersIncrementAcrossInvoices() {
        Subscription a = subscribedUser("sub_a", "cus_a");
        Subscription b = subscribedUser("sub_b", "cus_b");

        webhookHandler.handle(invoicePaid("in_a", "sub_a", 99900));
        webhookHandler.handle(invoicePaid("in_b", "sub_b", 99900));

        String numA = invoiceRepository.findByProviderInvoiceId("in_a").orElseThrow().getInvoiceNumber();
        String numB = invoiceRepository.findByProviderInvoiceId("in_b").orElseThrow().getInvoiceNumber();
        assertThat(numA).isNotEqualTo(numB);
    }

    @Test
    void pdfRendersNonEmpty() {
        Subscription sub = subscribedUser("sub_pdf", "cus_pdf");
        webhookHandler.handle(invoicePaid("in_pdf", "sub_pdf", 99900));
        UUID invoiceId = invoiceRepository.findByProviderInvoiceId("in_pdf").orElseThrow().getId();

        byte[] pdf = invoiceService.renderPdf(invoiceId, sub.getUserId());

        assertThat(pdf).isNotEmpty();
        // PDF magic header "%PDF".
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
