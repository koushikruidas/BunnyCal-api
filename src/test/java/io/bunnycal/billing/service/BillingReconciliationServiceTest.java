package io.bunnycal.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.billing.domain.BillingInterval;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.notification.BillingEventPublisher;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal;
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
class BillingReconciliationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SUB = "sub_1";
    private static final Instant T0 = Instant.parse("2026-07-24T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-24T12:05:00Z");

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock InvoiceService invoiceService;
    @Mock PlanService planService;
    @Mock BillingEventPublisher billingEventPublisher;
    @Mock PaymentAuditService auditService;
    @Mock TimeSource timeSource;

    private BillingReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new BillingReconciliationService(
                subscriptionRepository, invoiceService, planService, billingEventPublisher,
                auditService, new BillingProperties(true, "dodo", 7, null, null, null), timeSource);
        lenient().when(timeSource.now()).thenReturn(T1);
        lenient().when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Subscription incomplete() {
        return Subscription.builder()
                .id(UUID.randomUUID()).userId(USER_ID).planId(PLAN_ID)
                .status(SubscriptionStatus.INCOMPLETE).providerSubscriptionId(SUB).build();
    }

    private void lockReturns(Subscription s) {
        when(subscriptionRepository.findByProviderSubscriptionId(SUB)).thenReturn(Optional.of(s));
        when(subscriptionRepository.findByIdForUpdate(s.getId())).thenReturn(Optional.of(s));
    }

    private SubscriptionSnapshot snapshot(SubscriptionStatusSignal status, Instant providerUpdatedAt) {
        return new SubscriptionSnapshot(SUB, "cus_1", USER_ID.toString(), status,
                false, null, null, providerUpdatedAt);
    }

    @Test
    void activeSnapshot_activatesIncompleteSubscription() {
        Subscription sub = incomplete();
        lockReturns(sub);

        Optional<Subscription> result = service.applySubscriptionSnapshot(
                snapshot(SubscriptionStatusSignal.ACTIVE, null), T1, ReconciliationSource.REDIRECT);

        assertThat(result).isPresent();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getLastReconciliationSource()).isEqualTo("REDIRECT");
        assertThat(sub.getProviderObservedAt()).isEqualTo(T1);
    }

    @Test
    void staleSnapshot_isSkipped_andStatusUnchanged() {
        Subscription sub = incomplete();
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setProviderUpdatedAt(T1); // we already applied the newer T1 state
        lockReturns(sub);

        // An older snapshot (T0) that says CANCELLED must not overwrite the newer ACTIVE.
        service.applySubscriptionSnapshot(
                snapshot(SubscriptionStatusSignal.CANCELLED, T0), T0, ReconciliationSource.WEBHOOK);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(auditService).record(any(), any(), any(), org.mockito.ArgumentMatchers.eq("RECONCILE_SKIPPED_STALE"),
                any(), any());
    }

    @Test
    void newerSnapshot_isApplied() {
        Subscription sub = incomplete();
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setProviderUpdatedAt(T0);
        lockReturns(sub);

        service.applySubscriptionSnapshot(
                snapshot(SubscriptionStatusSignal.CANCELLED, T1), T1, ReconciliationSource.WEBHOOK);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void equalProviderTimestamps_laterObservationWins() {
        Subscription sub = incomplete();
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setProviderUpdatedAt(T0);
        sub.setProviderObservedAt(T1); // already observed at T1
        lockReturns(sub);

        // Same provider timestamp T0, but observed earlier (T0 < T1) → stale, skipped.
        service.applySubscriptionSnapshot(
                snapshot(SubscriptionStatusSignal.CANCELLED, T0), T0, ReconciliationSource.CRON);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void terminalSubscription_isNotReactivatedByActiveSnapshot() {
        Subscription sub = incomplete();
        sub.setStatus(SubscriptionStatus.CANCELLED);
        lockReturns(sub);

        service.applySubscriptionSnapshot(
                snapshot(SubscriptionStatusSignal.ACTIVE, null), T1, ReconciliationSource.CRON);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void noMatchingSubscription_returnsEmpty() {
        when(subscriptionRepository.findByProviderSubscriptionId(SUB)).thenReturn(Optional.empty());
        when(subscriptionRepository.findLiveByProviderCustomerId("cus_1")).thenReturn(Optional.empty());
        when(subscriptionRepository.findLiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        Optional<Subscription> result = service.applySubscriptionSnapshot(
                snapshot(SubscriptionStatusSignal.ACTIVE, null), T1, ReconciliationSource.WEBHOOK);

        assertThat(result).isEmpty();
    }

    @Test
    void succeededPayment_activatesAndRecordsReceipt_withNoWebhook() {
        Subscription sub = incomplete();
        lockReturns(sub);
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .id(PLAN_ID).billingInterval(BillingInterval.MONTH).build();
        lenient().when(planService.requireById(PLAN_ID)).thenReturn(plan);
        when(invoiceService.existsByProviderInvoiceId(any())).thenReturn(false);
        SubscriptionInvoice saved = SubscriptionInvoice.builder()
                .id(UUID.randomUUID()).invoiceNumber("BCR-1").build();
        when(invoiceService.recordPaidInvoice(any(), any())).thenReturn(saved);

        PaymentSnapshot payment = new PaymentSnapshot(
                "pay_1", PaymentSnapshot.PaymentStatus.SUCCEEDED, SUB, "cus_1",
                "inv_1", null, null, 99900, 0, 99900, "INR", T0, null);

        Optional<Subscription> result = service.applyPaymentSnapshot(payment, T1, ReconciliationSource.REDIRECT);

        assertThat(result).isPresent();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(invoiceService).recordPaidInvoice(any(), any());
        verify(billingEventPublisher).publishForInvoice(any(), any(), any(), any());
    }

    /**
     * A purchase made during a trial reports next_billing_date = trial end, which is not the
     * period the payment bought. The receipt must state a full plan interval instead, or a
     * yearly plan shows a ~14-day billing period.
     */
    @Test
    void yearlyPurchaseDuringTrial_recordsAFullYearNotTheTrialEnd() {
        Subscription sub = incomplete();
        lockReturns(sub);
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .id(PLAN_ID).billingInterval(BillingInterval.YEAR).build();
        lenient().when(planService.requireById(PLAN_ID)).thenReturn(plan);
        when(invoiceService.existsByProviderInvoiceId(any())).thenReturn(false);
        when(invoiceService.recordPaidInvoice(any(), any())).thenReturn(
                SubscriptionInvoice.builder().id(UUID.randomUUID()).invoiceNumber("BCR-1").build());

        Instant start = Instant.parse("2026-08-08T00:00:00Z");
        Instant trialEnd = Instant.parse("2026-08-22T00:00:00Z");
        PaymentSnapshot payment = new PaymentSnapshot(
                "pay_1", PaymentSnapshot.PaymentStatus.SUCCEEDED, SUB, "cus_1",
                "inv_1", null, null, 480000, 0, 480000, "USD", start, trialEnd);

        service.applyPaymentSnapshot(payment, T1, ReconciliationSource.REDIRECT);

        ArgumentCaptor<InvoiceService.PaidInvoiceInput> input =
                ArgumentCaptor.forClass(InvoiceService.PaidInvoiceInput.class);
        verify(invoiceService).recordPaidInvoice(any(), input.capture());
        assertThat(input.getValue().periodStart()).isEqualTo(start);
        assertThat(input.getValue().periodEnd()).isEqualTo(Instant.parse("2027-08-08T00:00:00Z"));
    }

    /** The monthly equivalent, so the interval is genuinely read from the plan. */
    @Test
    void monthlyPurchaseDuringTrial_recordsAFullMonth() {
        Subscription sub = incomplete();
        lockReturns(sub);
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .id(PLAN_ID).billingInterval(BillingInterval.MONTH).build();
        lenient().when(planService.requireById(PLAN_ID)).thenReturn(plan);
        when(invoiceService.existsByProviderInvoiceId(any())).thenReturn(false);
        when(invoiceService.recordPaidInvoice(any(), any())).thenReturn(
                SubscriptionInvoice.builder().id(UUID.randomUUID()).invoiceNumber("BCR-2").build());

        Instant start = Instant.parse("2026-08-08T00:00:00Z");
        PaymentSnapshot payment = new PaymentSnapshot(
                "pay_2", PaymentSnapshot.PaymentStatus.SUCCEEDED, SUB, "cus_1",
                "inv_2", null, null, 40000, 0, 40000, "USD",
                start, Instant.parse("2026-08-22T00:00:00Z"));

        service.applyPaymentSnapshot(payment, T1, ReconciliationSource.REDIRECT);

        ArgumentCaptor<InvoiceService.PaidInvoiceInput> input =
                ArgumentCaptor.forClass(InvoiceService.PaidInvoiceInput.class);
        verify(invoiceService).recordPaidInvoice(any(), input.capture());
        assertThat(input.getValue().periodEnd()).isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    void failedPayment_isIgnored() {
        PaymentSnapshot payment = new PaymentSnapshot(
                "pay_1", PaymentSnapshot.PaymentStatus.FAILED, SUB, "cus_1",
                null, null, null, 0, 0, 0, "INR", null, null);

        Optional<Subscription> result = service.applyPaymentSnapshot(payment, T1, ReconciliationSource.WEBHOOK);

        assertThat(result).isEmpty();
        verify(invoiceService, never()).recordPaidInvoice(any(), any());
    }
}
