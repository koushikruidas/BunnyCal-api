package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.PaymentTransaction;
import io.bunnycal.billing.domain.Refund;
import io.bunnycal.billing.domain.RefundReasonCode;
import io.bunnycal.billing.domain.RefundStatus;
import io.bunnycal.billing.domain.RefundType;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.billing.repository.PaymentTransactionRepository;
import io.bunnycal.billing.repository.RefundRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderRequests.RefundRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and reconciles refunds. A refund returns money against an invoice; it never
 * cancels the subscription (cancellation is a separate admin action). Amounts are
 * validated against the remaining refundable balance. Invoice status moves to
 * PARTIALLY_REFUNDED or REFUNDED based on the cumulative refunded amount.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final String ENTITY = "Refund";

    private final RefundRepository refundRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentAuditService auditService;
    @Nullable
    private final PaymentProvider paymentProvider;

    public RefundService(RefundRepository refundRepository,
                         SubscriptionInvoiceRepository invoiceRepository,
                         PaymentTransactionRepository transactionRepository,
                         PaymentAuditService auditService,
                         @Autowired(required = false) @Nullable PaymentProvider paymentProvider) {
        this.refundRepository = refundRepository;
        this.invoiceRepository = invoiceRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
        this.paymentProvider = paymentProvider;
    }

    /**
     * Issues a refund against an invoice. {@code amountMinor == null} means a full refund
     * of the remaining refundable balance. Calls the provider, records the refund, and
     * updates the invoice. Does NOT cancel the subscription.
     */
    @Transactional
    public Refund issueRefund(UUID invoiceId,
                              @Nullable Long amountMinor,
                              RefundReasonCode reasonCode,
                              String note,
                              UUID adminId) {
        if (paymentProvider == null) {
            throw new CustomException(ErrorCode.BILLING_DISABLED);
        }
        SubscriptionInvoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found."));
        if (invoice.getStatus() == InvoiceStatus.VOID || invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new CustomException(ErrorCode.INVOICE_NOT_REFUNDABLE);
        }

        long remaining = invoice.getTotalMinor() - invoice.getAmountRefundedMinor();
        long amount = amountMinor == null ? remaining : amountMinor;
        if (amount <= 0 || amount > remaining) {
            throw new CustomException(ErrorCode.REFUND_AMOUNT_INVALID);
        }

        PaymentTransaction tx = transactionRepository
                .findFirstByInvoiceIdOrderByOccurredAtDesc(invoiceId).orElse(null);
        String paymentIntentId = tx == null ? null : tx.getProviderPaymentIntentId();
        if (paymentIntentId == null) {
            throw new CustomException(ErrorCode.INVOICE_NOT_REFUNDABLE,
                    "No payment found to refund for this invoice.");
        }

        RefundResult result = paymentProvider.refund(
                new RefundRequest(paymentIntentId, amount, reasonCode.name()));

        boolean fullAfter = invoice.getAmountRefundedMinor() + amount >= invoice.getTotalMinor();
        Refund refund = refundRepository.save(Refund.builder()
                .invoiceId(invoiceId)
                .paymentTransactionId(tx.getId())
                .amountMinor(amount)
                .currency(invoice.getCurrency())
                .type(fullAfter ? RefundType.FULL : RefundType.PARTIAL)
                .reasonCode(reasonCode)
                .note(note)
                .providerRefundId(result.providerRefundId())
                .status(mapStatus(result.status()))
                .createdBy(PaymentAuditService.adminActor(adminId))
                .build());

        applyRefundToInvoice(invoice, amount);
        auditService.record(PaymentAuditService.adminActor(adminId), ENTITY, refund.getId(),
                "REFUND_ISSUED", null,
                Map.of("invoiceId", invoiceId.toString(), "amountMinor", amount, "reason", reasonCode.name()));
        log.info("billing.refund_issued invoiceId={} amountMinor={} reason={} providerRefundId={}",
                invoiceId, amount, reasonCode, result.providerRefundId());
        return refund;
    }

    /**
     * Reconciles a refund observed via webhook (charge.refunded), whether it originated
     * in-app or in the provider dashboard. Idempotent by providerRefundId.
     *
     * @param invoice            the invoice the charge belongs to
     * @param providerRefundId   the provider refund id (idempotency anchor)
     * @param totalRefundedMinor the cumulative amount refunded on the charge per provider
     */
    @Transactional
    public void reconcileFromWebhook(SubscriptionInvoice invoice,
                                     String providerRefundId,
                                     long totalRefundedMinor) {
        if (providerRefundId != null && refundRepository.existsByProviderRefundId(providerRefundId)) {
            return; // already recorded (e.g. in-app refund) — nothing to do
        }

        long alreadyRecorded = invoice.getAmountRefundedMinor();
        long delta = totalRefundedMinor - alreadyRecorded;
        if (delta <= 0) {
            return; // provider total not ahead of ours; nothing new
        }

        boolean fullAfter = totalRefundedMinor >= invoice.getTotalMinor();
        refundRepository.save(Refund.builder()
                .invoiceId(invoice.getId())
                .amountMinor(delta)
                .currency(invoice.getCurrency())
                .type(fullAfter ? RefundType.FULL : RefundType.PARTIAL)
                .reasonCode(RefundReasonCode.OTHER)
                .note("Reconciled from provider charge.refunded")
                .providerRefundId(providerRefundId)
                .status(RefundStatus.SUCCEEDED)
                .createdBy(PaymentAuditService.ACTOR_WEBHOOK)
                .build());

        applyRefundToInvoice(invoice, delta);
        auditService.record(PaymentAuditService.ACTOR_WEBHOOK, ENTITY, invoice.getId(),
                "REFUND_RECONCILED", null,
                Map.of("invoiceId", invoice.getId().toString(), "amountMinor", delta));
        log.info("billing.refund_reconciled invoiceId={} deltaMinor={} providerRefundId={}",
                invoice.getId(), delta, providerRefundId);
    }

    @Transactional(readOnly = true)
    public List<Refund> listForInvoice(UUID invoiceId) {
        return refundRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId);
    }

    /** Adds to the invoice's cumulative refunded amount and sets the derived status. */
    private void applyRefundToInvoice(SubscriptionInvoice invoice, long amount) {
        long refunded = invoice.getAmountRefundedMinor() + amount;
        invoice.setAmountRefundedMinor(refunded);
        invoice.setStatus(refunded >= invoice.getTotalMinor()
                ? InvoiceStatus.REFUNDED
                : InvoiceStatus.PARTIALLY_REFUNDED);
        invoiceRepository.save(invoice);
    }

    private static RefundStatus mapStatus(String providerStatus) {
        if (providerStatus == null) {
            return RefundStatus.PENDING;
        }
        return switch (providerStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> RefundStatus.PENDING;
        };
    }
}
