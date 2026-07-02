package io.bunnycal.payments.provider;

/**
 * Provider-neutral billing event types. Each {@link PaymentProvider} maps its own
 * raw event-type strings (Stripe's {@code invoice.paid}, Dodo's {@code payment.succeeded},
 * etc.) onto this enum in {@code verifyWebhook}, so the domain webhook handler never sees
 * a provider-specific string.
 */
public enum BillingEventType {

    /** Hosted checkout completed — links the provider subscription/customer to our row. */
    CHECKOUT_COMPLETED,

    /** A subscription was created or its status/period changed. */
    SUBSCRIPTION_UPSERTED,

    /** A subscription was terminated by the provider. */
    SUBSCRIPTION_DELETED,

    /** A renewal/initial invoice was paid — record the immutable invoice + transaction. */
    INVOICE_PAID,

    /** A renewal payment failed — enter the dunning grace window. */
    INVOICE_FAILED,

    /** A refund was processed on a charge — reconcile against our invoice. */
    REFUND_PROCESSED,

    /** Recognized-but-unhandled, or unrecognized: ignored (still marked PROCESSED). */
    UNKNOWN
}
