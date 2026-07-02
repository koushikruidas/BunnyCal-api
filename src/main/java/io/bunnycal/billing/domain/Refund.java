package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A refund against an invoice. Does not cancel the subscription. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "refunds",
        indexes = @Index(name = "idx_refunds_invoice", columnList = "invoice_id"))
public class Refund extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "payment_transaction_id")
    private UUID paymentTransactionId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    private RefundReasonCode reasonCode;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "provider_refund_id", length = 255)
    private String providerRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundStatus status;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;
}
