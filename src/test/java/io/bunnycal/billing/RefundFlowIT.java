package io.bunnycal.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.RefundReasonCode;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.InvoiceService;
import io.bunnycal.billing.service.RefundService;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderRequests;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventHandler;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Milestone-5 verification: full/partial refunds, no auto-cancel, over-amount rejection,
 * and charge.refunded reconciliation idempotency. Uses a fake PaymentProvider so the
 * refund call doesn't hit Stripe.
 */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers
@Import(RefundFlowIT.FakeProviderConfig.class)
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
class RefundFlowIT {

    @TestConfiguration
    static class FakeProviderConfig {
        @Bean
        @Primary
        PaymentProvider fakePaymentProvider() {
            return new PaymentProvider() {
                @Override
                public ProviderRequests.CustomerRef createCustomer(ProviderRequests.CreateCustomerRequest r) {
                    return new ProviderRequests.CustomerRef("cus_fake");
                }

                @Override
                public ProviderRequests.CheckoutSession createCheckoutSession(ProviderRequests.CheckoutSessionRequest r) {
                    return new ProviderRequests.CheckoutSession("cs_fake", "https://fake/checkout");
                }

                @Override
                public ProviderRequests.PortalSession createPortalSession(ProviderRequests.PortalSessionRequest r) {
                    return new ProviderRequests.PortalSession("https://fake/portal");
                }

                @Override
                public void cancelSubscription(ProviderRequests.CancelSubscriptionRequest r) {
                }

                @Override
                public ProviderRequests.RefundResult refund(ProviderRequests.RefundRequest r) {
                    return new ProviderRequests.RefundResult("re_" + UUID.randomUUID(), "succeeded");
                }

                @Override
                public ProviderWebhookEvent verifyWebhook(byte[] payload, java.util.Map<String, String> headers) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

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
    @Autowired RefundService refundService;
    @Autowired WebhookEventHandler webhookHandler;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE refunds, payment_transactions, subscription_invoices, payment_methods, subscriptions, payment_audit_logs CASCADE");
        jdbc.execute("TRUNCATE TABLE users CASCADE");
    }

    private record Fixture(Subscription subscription, SubscriptionInvoice invoice) {
    }

    private Fixture paidInvoice(String subId, long total) {
        User user = userRepository.save(User.builder()
                .email(UUID.randomUUID() + "@example.com").name("Test User").timezone("UTC").build());
        Subscription sub = subscriptionService.ensureSubscription(user.getId()).orElseThrow();
        sub.setProviderCustomerId("cus_" + subId);
        sub.setProviderSubscriptionId(subId);
        subscriptionRepository.save(sub);

        SubscriptionInvoice invoice = invoiceService.recordPaidInvoice(sub,
                new InvoiceService.PaidInvoiceInput(
                        "in_" + subId, "pi_" + subId, total, 0, total, "INR", null, null));
        return new Fixture(sub, invoice);
    }

    @Test
    void fullRefundMarksInvoiceRefundedAndDoesNotCancel() {
        Fixture f = paidInvoice("sub_full", 99900);

        refundService.issueRefund(f.invoice().getId(), null, RefundReasonCode.CUSTOMER_REQUEST, "n/a", UUID.randomUUID());

        SubscriptionInvoice after = invoiceRepository.findById(f.invoice().getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.REFUNDED);
        assertThat(after.getAmountRefundedMinor()).isEqualTo(99900);
        // Subscription is untouched by a refund.
        Subscription sub = subscriptionRepository.findById(f.subscription().getId()).orElseThrow();
        assertThat(sub.getStatus()).isNotEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void partialRefundMarksPartiallyRefunded() {
        Fixture f = paidInvoice("sub_partial", 99900);

        refundService.issueRefund(f.invoice().getId(), 30000L, RefundReasonCode.GOODWILL, null, UUID.randomUUID());

        SubscriptionInvoice after = invoiceRepository.findById(f.invoice().getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_REFUNDED);
        assertThat(after.getAmountRefundedMinor()).isEqualTo(30000);
    }

    @Test
    void refundOverRemainingIsRejected() {
        Fixture f = paidInvoice("sub_over", 50000);
        refundService.issueRefund(f.invoice().getId(), 40000L, RefundReasonCode.OTHER, null, UUID.randomUUID());

        assertThatThrownBy(() -> refundService.issueRefund(
                f.invoice().getId(), 20000L, RefundReasonCode.OTHER, null, UUID.randomUUID()))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void chargeRefundedWebhookReconcilesIdempotently() {
        Fixture f = paidInvoice("sub_wh", 99900);
        ProviderWebhookEvent event = new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID(),
                "charge.refunded",
                io.bunnycal.payments.provider.BillingEventType.REFUND_PROCESSED,
                "{}",
                ProviderWebhookEvent.Data.builder()
                        .refundProviderInvoiceId("in_sub_wh")
                        .providerRefundId("re_wh_1")
                        .amountRefundedMinor(99900)
                        .build());

        webhookHandler.handle(event);
        webhookHandler.handle(event); // redelivery

        SubscriptionInvoice after = invoiceRepository.findById(f.invoice().getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.REFUNDED);
        assertThat(after.getAmountRefundedMinor()).isEqualTo(99900);
        Long refundRows = jdbc.queryForObject(
                "SELECT count(*) FROM refunds WHERE invoice_id = ?", Long.class, f.invoice().getId());
        assertThat(refundRows).isEqualTo(1L);
    }
}
