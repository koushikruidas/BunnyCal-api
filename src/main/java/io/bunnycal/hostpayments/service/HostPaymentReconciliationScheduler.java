package io.bunnycal.hostpayments.service;

import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.repository.BookingPaymentRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostPaymentReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(HostPaymentReconciliationScheduler.class);
    private final BookingPaymentRepository repository;
    private final HostPaymentProviderRegistry providers;
    private final HostPaymentLifecycleService lifecycleService;
    private final PaymentAuditService auditService;

    public HostPaymentReconciliationScheduler(BookingPaymentRepository repository, HostPaymentProviderRegistry providers,
                                              HostPaymentLifecycleService lifecycleService,
                                              PaymentAuditService auditService) {
        this.repository = repository;
        this.providers = providers;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${commerce.reconciliation.fixed-delay-ms:30000}")
    @SchedulerLock(name = "host_payment_reconciliation", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void reconcile() {
        var due = repository.findTop200ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                List.of(BookingPaymentStatus.CANCEL_REQUESTED, BookingPaymentStatus.REFUND_REQUIRED),
                Instant.now().minus(5, ChronoUnit.SECONDS));
        for (var payment : due) {
            try {
                HostPaymentProvider provider = providers.require(payment.getProvider());
                String auditAction;
                if (payment.getStatus() == BookingPaymentStatus.CANCEL_REQUESTED) {
                    if (payment.getProviderPaymentId() != null) {
                        try {
                            provider.cancelPayment(payment.getProviderAccountId(), payment.getProviderPaymentId());
                        } catch (RuntimeException cancellationRejected) {
                            // The intent can settle between hold expiry and cancellation. Re-read it;
                            // a succeeded payment is converted to REFUND_REQUIRED by the lifecycle.
                            lifecycleService.handleProviderPayment(payment.getProvider(), payment.getProviderAccountId(),
                                    payment.getProviderPaymentId(), "reconciliation", null, null);
                            continue;
                        }
                    }
                    payment.setStatus(BookingPaymentStatus.CANCELLED);
                    auditAction = "PAYMENT_CANCELLED";
                } else {
                    provider.refundPayment(payment.getProviderAccountId(), payment.getProviderPaymentId(),
                            "host-payment-refund-" + payment.getId());
                    payment.setStatus(BookingPaymentStatus.REFUNDED);
                    payment.setRefundedAt(Instant.now());
                    auditAction = "PAYMENT_REFUNDED";
                }
                repository.save(payment);
                auditService.recordHostCommerce(PaymentAuditService.ACTOR_SYSTEM, "BookingPayment", payment.getId(),
                        auditAction, null, java.util.Map.of("status", payment.getStatus().name()));
            } catch (RuntimeException retryLater) {
                // Leave the durable state unchanged; the next sweep retries with the same provider idempotency key.
                log.warn("host_payment_compensation_retry paymentId={} status={} provider={}",
                        payment.getId(), payment.getStatus(), payment.getProvider(), retryLater);
            }
        }

        var stale = repository.findTop200ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                List.of(BookingPaymentStatus.CREATED, BookingPaymentStatus.REQUIRES_ACTION,
                        BookingPaymentStatus.PROCESSING, BookingPaymentStatus.FAILED),
                Instant.now().minus(30, ChronoUnit.SECONDS));
        for (var payment : stale) {
            if (payment.getExpiresAt().isBefore(Instant.now())) {
                payment.setStatus(BookingPaymentStatus.CANCEL_REQUESTED);
                repository.save(payment);
            } else if (payment.getProviderPaymentId() != null) {
                try {
                    lifecycleService.handleProviderPayment(payment.getProvider(), payment.getProviderAccountId(),
                            payment.getProviderPaymentId(), "reconciliation", null, null);
                } catch (RuntimeException retryLater) {
                    // Keep the last known state and retry on the next sweep.
                    log.warn("host_payment_reconciliation_retry paymentId={} status={} provider={}",
                            payment.getId(), payment.getStatus(), payment.getProvider(), retryLater);
                }
            }
        }
    }
}
