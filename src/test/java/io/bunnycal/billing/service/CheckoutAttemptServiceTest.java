package io.bunnycal.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.bunnycal.billing.domain.CheckoutAttempt;
import io.bunnycal.billing.domain.CheckoutAttemptStatus;
import io.bunnycal.billing.repository.CheckoutAttemptRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.provider.BillingProviderReader;
import io.bunnycal.payments.provider.ProviderSnapshots.CheckoutSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckoutAttemptServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final String SESSION = "cs_1";

    @Mock CheckoutAttemptRepository attemptRepository;
    @Mock SubscriptionService subscriptionService;
    @Mock PlanService planService;
    @Mock BillingReconciliationService reconciliationService;
    @Mock BillingProviderReader providerReader;
    @Mock TimeSource timeSource;

    private CheckoutAttemptService service;

    @BeforeEach
    void setUp() {
        service = new CheckoutAttemptService(attemptRepository, subscriptionService, planService,
                reconciliationService, providerReader, timeSource);
        lenient().when(timeSource.now()).thenReturn(NOW);
        lenient().when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CheckoutAttempt openAttempt() {
        return CheckoutAttempt.builder()
                .id(UUID.randomUUID()).userId(USER_ID).planId(UUID.randomUUID())
                .expectedAmountMinor(99900).currency("INR")
                .providerSessionId(SESSION).status(CheckoutAttemptStatus.OPEN).build();
    }

    @Test
    void reconcile_completedCheckout_withNoWebhook_marksAttemptSucceeded() {
        CheckoutAttempt attempt = openAttempt();
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(providerReader.getCheckoutSession(SESSION)).thenReturn(Optional.of(new CheckoutSnapshot(
                SESSION, CheckoutSnapshot.CheckoutStatus.COMPLETED, "sub_1", "cus_1", "pay_1",
                USER_ID.toString(), 99900, "INR")));
        when(providerReader.getSubscription("sub_1")).thenReturn(Optional.empty());
        when(providerReader.getPayment("pay_1")).thenReturn(Optional.of(new PaymentSnapshot(
                "pay_1", PaymentSnapshot.PaymentStatus.SUCCEEDED, "sub_1", "cus_1", "inv_1",
                null, null, 99900, 0, 99900, "INR", null, null)));

        CheckoutAttempt result = service.reconcile(attempt.getId(), ReconciliationSource.REDIRECT);

        assertThat(result.getStatus()).isEqualTo(CheckoutAttemptStatus.SUCCEEDED);
        assertThat(result.getProviderPaymentId()).isEqualTo("pay_1");
    }

    @Test
    void reconcile_openCheckout_marksProcessing() {
        CheckoutAttempt attempt = openAttempt();
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(providerReader.getCheckoutSession(SESSION)).thenReturn(Optional.of(new CheckoutSnapshot(
                SESSION, CheckoutSnapshot.CheckoutStatus.OPEN, null, "cus_1", null,
                USER_ID.toString(), 99900, "INR")));

        CheckoutAttempt result = service.reconcile(attempt.getId(), ReconciliationSource.REDIRECT);

        assertThat(result.getStatus()).isEqualTo(CheckoutAttemptStatus.PROCESSING);
    }

    @Test
    void reconcile_currencyMismatch_failsAttempt() {
        CheckoutAttempt attempt = openAttempt();
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(providerReader.getCheckoutSession(SESSION)).thenReturn(Optional.of(new CheckoutSnapshot(
                SESSION, CheckoutSnapshot.CheckoutStatus.COMPLETED, "sub_1", "cus_1", "pay_1",
                USER_ID.toString(), 99900, "USD"))); // expected INR

        CheckoutAttempt result = service.reconcile(attempt.getId(), ReconciliationSource.REDIRECT);

        assertThat(result.getStatus()).isEqualTo(CheckoutAttemptStatus.FAILED);
    }

    @Test
    void reconcile_alreadySucceeded_shortCircuits() {
        CheckoutAttempt attempt = openAttempt();
        attempt.succeed(NOW);
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

        CheckoutAttempt result = service.reconcile(attempt.getId(), ReconciliationSource.REDIRECT);

        assertThat(result.getStatus()).isEqualTo(CheckoutAttemptStatus.SUCCEEDED);
    }
}
