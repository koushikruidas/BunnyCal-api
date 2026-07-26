package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single checkout attempt. Created before the provider redirect so a completed payment can be
 * verified and recovered independently of any webhook. Provider identifiers are filled in as the
 * attempt advances; state transitions go through the guarded intent methods below.
 *
 * <p>One open (non-terminal) attempt per user is enforced by a partial unique index, so a user who
 * refreshes mid-checkout resumes the same attempt rather than creating duplicates.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "billing_checkout_attempts",
        indexes = {
            @Index(name = "idx_checkout_attempts_session", columnList = "provider_session_id")
        })
public class CheckoutAttempt extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "expected_amount_minor", nullable = false)
    private long expectedAmountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "provider_session_id", length = 255)
    private String providerSessionId;

    @Column(name = "provider_payment_id", length = 255)
    private String providerPaymentId;

    @Column(name = "provider_subscription_id", length = 255)
    private String providerSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CheckoutAttemptStatus status;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "redirected_at")
    private Instant redirectedAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0;

    // -----------------------------------------------------------------------------------------
    // Guarded transitions. CREATED -> OPEN -> PROCESSING -> SUCCEEDED is the happy path; any
    // non-terminal state may FAIL/EXPIRE. A terminal FAILED/EXPIRED attempt can still be recovered
    // to SUCCEEDED by a late-verified payment, but only via the explicit recover() path.
    // -----------------------------------------------------------------------------------------

    private static final Set<CheckoutAttemptStatus> OPENABLE_FROM =
            EnumSet.of(CheckoutAttemptStatus.CREATED, CheckoutAttemptStatus.OPEN);
    private static final Set<CheckoutAttemptStatus> PROCESSABLE_FROM =
            EnumSet.of(CheckoutAttemptStatus.OPEN, CheckoutAttemptStatus.PROCESSING);
    private static final Set<CheckoutAttemptStatus> SUCCEEDABLE_FROM =
            EnumSet.of(CheckoutAttemptStatus.OPEN, CheckoutAttemptStatus.PROCESSING,
                    CheckoutAttemptStatus.SUCCEEDED);

    /** Mark the provider session created and the user redirected. */
    public void markRedirected(Instant now) {
        guard(OPENABLE_FROM, CheckoutAttemptStatus.OPEN);
        this.status = CheckoutAttemptStatus.OPEN;
        if (this.redirectedAt == null) {
            this.redirectedAt = now;
        }
    }

    /** A provider read shows payment in progress but not yet confirmed. */
    public void markProcessing() {
        guard(PROCESSABLE_FROM, CheckoutAttemptStatus.PROCESSING);
        this.status = CheckoutAttemptStatus.PROCESSING;
    }

    /** Payment verified. Legal from OPEN/PROCESSING and idempotent from SUCCEEDED. */
    public void succeed(Instant now) {
        guard(SUCCEEDABLE_FROM, CheckoutAttemptStatus.SUCCEEDED);
        this.status = CheckoutAttemptStatus.SUCCEEDED;
        if (this.succeededAt == null) {
            this.succeededAt = now;
        }
    }

    /** Fail (non-terminal → FAILED), idempotent if already FAILED. */
    public void fail(String reason) {
        if (status == CheckoutAttemptStatus.FAILED) {
            return;
        }
        if (status == CheckoutAttemptStatus.SUCCEEDED || status == CheckoutAttemptStatus.EXPIRED) {
            throw new IllegalCheckoutAttemptTransitionException(status, CheckoutAttemptStatus.FAILED);
        }
        this.status = CheckoutAttemptStatus.FAILED;
        this.lastError = reason;
    }

    /** Expire (non-terminal → EXPIRED), idempotent if already EXPIRED. */
    public void expire() {
        if (status == CheckoutAttemptStatus.EXPIRED) {
            return;
        }
        if (status == CheckoutAttemptStatus.SUCCEEDED || status == CheckoutAttemptStatus.FAILED) {
            throw new IllegalCheckoutAttemptTransitionException(status, CheckoutAttemptStatus.EXPIRED);
        }
        this.status = CheckoutAttemptStatus.EXPIRED;
    }

    /**
     * Explicit audited recovery of a terminal FAILED/EXPIRED attempt into SUCCEEDED, used when a
     * payment is verified late (after the attempt was already failed/expired). Deliberately the
     * only way to un-terminalize an attempt, so ordinary reconciliation cannot do it by accident.
     */
    public void recoverToSucceeded(Instant now) {
        if (status == CheckoutAttemptStatus.SUCCEEDED) {
            return;
        }
        if (status != CheckoutAttemptStatus.FAILED && status != CheckoutAttemptStatus.EXPIRED) {
            throw new IllegalCheckoutAttemptTransitionException(status, CheckoutAttemptStatus.SUCCEEDED);
        }
        this.status = CheckoutAttemptStatus.SUCCEEDED;
        this.lastError = null;
        if (this.succeededAt == null) {
            this.succeededAt = now;
        }
    }

    private void guard(Set<CheckoutAttemptStatus> allowedFrom, CheckoutAttemptStatus to) {
        if (!allowedFrom.contains(status)) {
            throw new IllegalCheckoutAttemptTransitionException(status, to);
        }
    }
}
