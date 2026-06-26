package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.PaymentTransaction;
import io.bunnycal.billing.domain.PaymentTransactionStatus;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.billing.repository.PaymentTransactionRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads immutable invoice records. Invoice creation is idempotent by
 * {@code providerInvoiceId} (a redelivered {@code invoice.paid} webhook does not create a
 * duplicate). Invoice numbers come from a dedicated Postgres sequence, formatted
 * {@code BC-000001}.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final String ENTITY = "Invoice";
    private static final String NUMBER_PREFIX = "BC-";

    private final SubscriptionInvoiceRepository invoiceRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final TimeSource timeSource;
    private final PaymentAuditService auditService;
    private final io.bunnycal.auth.repository.UserRepository userRepository;
    private final io.bunnycal.billing.repository.SubscriptionRepository subscriptionRepository;
    private final PlanService planService;
    private final io.bunnycal.billing.invoice.PdfInvoiceGenerator pdfInvoiceGenerator;

    /**
     * Data extracted from a provider's paid invoice, in neutral terms.
     *
     * @param providerInvoiceId       provider invoice id (idempotency anchor)
     * @param providerPaymentIntentId payment intent for the linked transaction (nullable)
     * @param subtotalMinor           pre-discount amount in minor units
     * @param discountMinor           discount applied in minor units
     * @param totalMinor              charged amount in minor units
     * @param currency                ISO currency code
     * @param periodStart             billing period start (nullable)
     * @param periodEnd               billing period end (nullable)
     */
    public record PaidInvoiceInput(
            String providerInvoiceId,
            String providerPaymentIntentId,
            long subtotalMinor,
            long discountMinor,
            long totalMinor,
            String currency,
            Instant periodStart,
            Instant periodEnd) {
    }

    /**
     * Records a paid invoice (and its payment transaction) for a subscription, idempotently.
     * Returns the existing row if the provider invoice was already recorded.
     */
    @Transactional
    public SubscriptionInvoice recordPaidInvoice(Subscription subscription, PaidInvoiceInput input) {
        if (input.providerInvoiceId() != null) {
            var existing = invoiceRepository.findByProviderInvoiceId(input.providerInvoiceId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                .subscriptionId(subscription.getId())
                .userId(subscription.getUserId())
                .teamId(subscription.getTeamId())
                .invoiceNumber(formatNumber(invoiceRepository.nextInvoiceNumber()))
                .providerInvoiceId(input.providerInvoiceId())
                .status(InvoiceStatus.PAID)
                .periodStart(input.periodStart())
                .periodEnd(input.periodEnd())
                .subtotalMinor(input.subtotalMinor())
                .discountMinor(input.discountMinor())
                .taxMinor(0)
                .totalMinor(input.totalMinor())
                .currency(input.currency())
                .issuedAt(timeSource.now())
                .build();

        SubscriptionInvoice saved;
        try {
            saved = invoiceRepository.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException race) {
            // Concurrent redelivery created it first; return that row.
            return invoiceRepository.findByProviderInvoiceId(input.providerInvoiceId())
                    .orElseThrow(() -> race);
        }

        PaymentTransaction tx = PaymentTransaction.builder()
                .subscriptionId(subscription.getId())
                .invoiceId(saved.getId())
                .providerPaymentIntentId(input.providerPaymentIntentId())
                .amountMinor(input.totalMinor())
                .currency(input.currency())
                .status(PaymentTransactionStatus.SUCCEEDED)
                .occurredAt(timeSource.now())
                .build();
        if (input.providerPaymentIntentId() == null
                || !transactionRepository.existsByProviderPaymentIntentId(input.providerPaymentIntentId())) {
            transactionRepository.save(tx);
        }

        auditService.record(PaymentAuditService.ACTOR_WEBHOOK, ENTITY, saved.getId(), "INVOICE_ISSUED",
                null, Map.of("number", saved.getInvoiceNumber(), "totalMinor", saved.getTotalMinor()));
        log.info("billing.invoice_issued number={} subscriptionId={} totalMinor={}",
                saved.getInvoiceNumber(), subscription.getId(), saved.getTotalMinor());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionInvoice> listForUser(UUID userId) {
        return invoiceRepository.findByUserIdOrderByIssuedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public SubscriptionInvoice requireForUser(UUID invoiceId, UUID userId) {
        return invoiceRepository.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Invoice not found."));
    }

    /** Renders the branded PDF for an invoice the user owns. */
    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID invoiceId, UUID userId) {
        SubscriptionInvoice invoice = requireForUser(invoiceId, userId);
        io.bunnycal.auth.domain.user.User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));
        String planName = subscriptionRepository.findById(invoice.getSubscriptionId())
                .map(s -> planService.requireById(s.getPlanId()).getName())
                .orElse("Subscription");
        return pdfInvoiceGenerator.generate(invoice,
                new io.bunnycal.billing.invoice.PdfInvoiceGenerator.InvoiceContext(
                        user.getName(), user.getEmail(), planName));
    }

    private String formatNumber(long seq) {
        return NUMBER_PREFIX + String.format("%06d", seq);
    }
}
