package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable invoice record. The financial facts (period, subtotal, discount, tax, total,
 * currency) are set once at issuance and never changed. Only refund columns and status
 * are updated later (by the refund flow in M5).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "subscription_invoices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_subscription_invoices_number", columnNames = "invoice_number"),
        indexes = @Index(name = "idx_subscription_invoices_user", columnList = "user_id,issued_at"))
public class SubscriptionInvoice extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "invoice_number", nullable = false, length = 32)
    private String invoiceNumber;

    @Column(name = "provider_invoice_id", length = 255)
    private String providerInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private InvoiceStatus status;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "subtotal_minor", nullable = false)
    @Builder.Default
    private long subtotalMinor = 0;

    @Column(name = "discount_minor", nullable = false)
    @Builder.Default
    private long discountMinor = 0;

    @Column(name = "tax_minor", nullable = false)
    @Builder.Default
    private long taxMinor = 0;

    @Column(name = "total_minor", nullable = false)
    @Builder.Default
    private long totalMinor = 0;

    @Column(name = "amount_refunded_minor", nullable = false)
    @Builder.Default
    private long amountRefundedMinor = 0;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "pdf_object_key", length = 512)
    private String pdfObjectKey;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();
}
