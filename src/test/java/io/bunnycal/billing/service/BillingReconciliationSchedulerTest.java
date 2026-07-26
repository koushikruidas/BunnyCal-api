package io.bunnycal.billing.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.CheckoutAttemptRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.BillingProviderReader;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot.PaymentStatus;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BillingReconciliationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock CheckoutAttemptRepository checkoutAttemptRepository;
    @Mock BillingReconciliationService reconciliationService;
    @Mock CheckoutAttemptService checkoutAttemptService;
    @Mock BillingProviderReader providerReader;
    @Mock TimeSource timeSource;

    private BillingReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BillingReconciliationScheduler(
                subscriptionRepository, checkoutAttemptRepository, reconciliationService,
                checkoutAttemptService, providerReader,
                new BillingProperties(true, "dodo", 7, null, null, null), timeSource);
        lenient().when(timeSource.now()).thenReturn(NOW);
        lenient().when(checkoutAttemptRepository.findStaleOpen(any())).thenReturn(List.of());
    }

    @Test
    void reReadsAndReconcilesAStaleSubscription() {
        Subscription stale = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.INCOMPLETE).providerSubscriptionId("sub_stale")
                .providerCustomerId("cus_1").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any()))
                .thenReturn(List.of(stale));
        when(providerReader.getSubscription("sub_stale")).thenReturn(Optional.of(
                new SubscriptionSnapshot("sub_stale", "cus_1", stale.getUserId().toString(),
                        SubscriptionStatusSignal.ACTIVE, false, null, null, null)));

        scheduler.reconcileStale();

        verify(reconciliationService).applySubscriptionSnapshot(
                any(), any(), eq(ReconciliationSource.CRON));
    }

    @Test
    void skipsWhenProviderHasNoSuchSubscription() {
        Subscription stale = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.INCOMPLETE).providerSubscriptionId("sub_gone").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any()))
                .thenReturn(List.of(stale));
        when(providerReader.getSubscription("sub_gone")).thenReturn(Optional.empty());

        scheduler.reconcileStale();

        verify(reconciliationService, never()).applySubscriptionSnapshot(any(), any(), any());
    }

    @Test
    void oneFailingSubscriptionDoesNotStopTheSweep() {
        Subscription bad = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.PAST_DUE).providerSubscriptionId("sub_bad").build();
        Subscription good = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.INCOMPLETE).providerSubscriptionId("sub_good").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any()))
                .thenReturn(List.of(bad, good));
        when(providerReader.getSubscription("sub_bad")).thenThrow(new RuntimeException("provider down"));
        when(providerReader.getSubscription("sub_good")).thenReturn(Optional.of(
                new SubscriptionSnapshot("sub_good", null, good.getUserId().toString(),
                        SubscriptionStatusSignal.ACTIVE, false, null, null, null)));

        scheduler.reconcileStale();

        // The good one is still reconciled despite the bad one throwing.
        verify(reconciliationService).applySubscriptionSnapshot(
                any(), any(), eq(ReconciliationSource.CRON));
    }

    @Test
    void recoversStuckRowWithNoSubscriptionIdByReadingCustomerSubscriptions() {
        // The dropped-webhook incident: checkout paid, webhook never arrived, so the local row is
        // INCOMPLETE with NO provider_subscription_id — only the customer id is known. Reading by id
        // is impossible; the sweep must fall back to listing the customer's subscriptions.
        Subscription stuck = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.INCOMPLETE)
                .providerSubscriptionId(null)
                .providerCustomerId("cus_1").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any()))
                .thenReturn(List.of(stuck));
        when(providerReader.listSubscriptionsForCustomer("cus_1")).thenReturn(List.of(
                new SubscriptionSnapshot("sub_live", "cus_1", stuck.getUserId().toString(),
                        SubscriptionStatusSignal.ACTIVE, false, null, null, NOW)));
        // The row heals to ACTIVE with the learned subscription id.
        Subscription healed = Subscription.builder()
                .id(stuck.getId()).userId(stuck.getUserId())
                .status(SubscriptionStatus.ACTIVE).providerSubscriptionId("sub_live")
                .providerCustomerId("cus_1").build();
        when(reconciliationService.applySubscriptionSnapshot(any(), any(), eq(ReconciliationSource.CRON)))
                .thenReturn(Optional.of(healed));
        // The payment.succeeded webhook was ALSO lost: the sub has no invoice. The sweep must
        // re-drive the latest succeeded payment so the receipt is written.
        when(providerReader.listPaymentsForSubscription("sub_live")).thenReturn(List.of(
                paymentSnapshot("pay_1", "sub_live", PaymentStatus.SUCCEEDED, NOW)));

        scheduler.reconcileStale();

        verify(reconciliationService).applySubscriptionSnapshot(
                any(), any(), eq(ReconciliationSource.CRON));
        // Never attempted a by-id read for a row that has no id.
        verify(providerReader, never()).getSubscription(any());
        // The receipt gap is closed: the succeeded payment is applied.
        verify(reconciliationService).applyPaymentSnapshot(
                any(), any(), eq(ReconciliationSource.CRON));
    }

    @Test
    void backfillsReceiptWithLatestSucceededPaymentWhenNewlyActivated() {
        Subscription stale = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.INCOMPLETE).providerSubscriptionId("sub_1")
                .providerCustomerId("cus_1").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any())).thenReturn(List.of(stale));
        when(providerReader.getSubscription("sub_1")).thenReturn(Optional.of(
                new SubscriptionSnapshot("sub_1", "cus_1", stale.getUserId().toString(),
                        SubscriptionStatusSignal.ACTIVE, false, null, null, NOW)));
        when(reconciliationService.applySubscriptionSnapshot(any(), any(), any())).thenReturn(
                Optional.of(active("sub_1")));
        // Two succeeded payments; the later one (by period start) must win.
        when(providerReader.listPaymentsForSubscription("sub_1")).thenReturn(List.of(
                paymentSnapshot("pay_old", "sub_1", PaymentStatus.SUCCEEDED, NOW.minusSeconds(86400)),
                paymentSnapshot("pay_new", "sub_1", PaymentStatus.SUCCEEDED, NOW),
                paymentSnapshot("pay_bad", "sub_1", PaymentStatus.FAILED, NOW.plusSeconds(10))));

        scheduler.reconcileStale();

        org.mockito.ArgumentCaptor<io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot> captor =
                org.mockito.ArgumentCaptor.forClass(
                        io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot.class);
        verify(reconciliationService).applyPaymentSnapshot(
                captor.capture(), any(), eq(ReconciliationSource.CRON));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().providerPaymentId()).isEqualTo("pay_new");
    }

    @Test
    void doesNotBackfillReceiptForSteadyStateActiveSubscription() {
        // An already-ACTIVE row being re-observed must NOT re-list payments every sweep.
        Subscription stale = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE).providerSubscriptionId("sub_1")
                .providerCustomerId("cus_1").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any())).thenReturn(List.of(stale));
        when(providerReader.getSubscription("sub_1")).thenReturn(Optional.of(
                new SubscriptionSnapshot("sub_1", "cus_1", stale.getUserId().toString(),
                        SubscriptionStatusSignal.ACTIVE, false, null, null, NOW)));
        when(reconciliationService.applySubscriptionSnapshot(any(), any(), any())).thenReturn(
                Optional.of(active("sub_1")));

        scheduler.reconcileStale();

        verify(providerReader, never()).listPaymentsForSubscription(any());
        verify(reconciliationService, never()).applyPaymentSnapshot(any(), any(), any());
    }

    @Test
    void leavesStuckRowAloneWhenCustomerHasNoLiveSubscription() {
        Subscription stuck = Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.INCOMPLETE)
                .providerSubscriptionId(null)
                .providerCustomerId("cus_1").build();
        when(subscriptionRepository.findStaleForReconciliation(any(), any()))
                .thenReturn(List.of(stuck));
        when(providerReader.listSubscriptionsForCustomer("cus_1")).thenReturn(List.of(
                new SubscriptionSnapshot("sub_dead", "cus_1", stuck.getUserId().toString(),
                        SubscriptionStatusSignal.CANCELLED, false, null, null, NOW)));

        scheduler.reconcileStale();

        verify(reconciliationService, never()).applySubscriptionSnapshot(any(), any(), any());
    }

    @Test
    void resolvesStaleOpenCheckoutAttempts() {
        when(subscriptionRepository.findStaleForReconciliation(any(), any())).thenReturn(List.of());
        io.bunnycal.billing.domain.CheckoutAttempt attempt =
                io.bunnycal.billing.domain.CheckoutAttempt.builder().id(UUID.randomUUID()).build();
        when(checkoutAttemptRepository.findStaleOpen(any())).thenReturn(List.of(attempt));

        scheduler.reconcileStale();

        verify(checkoutAttemptService).reconcile(attempt.getId(), ReconciliationSource.CRON);
    }

    private static Subscription active(String providerSubscriptionId) {
        return Subscription.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE)
                .providerSubscriptionId(providerSubscriptionId)
                .providerCustomerId("cus_1").build();
    }

    private static PaymentSnapshot paymentSnapshot(
            String paymentId, String subscriptionId, PaymentStatus status, Instant periodStart) {
        return new PaymentSnapshot(paymentId, status, subscriptionId, "cus_1", paymentId,
                null, null, 500, 0, 590, "INR", periodStart, null);
    }
}
