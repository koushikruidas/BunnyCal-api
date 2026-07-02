package io.bunnycal.payments.provider;

/**
 * Wraps provider-side (e.g. Stripe API) failures so the billing domain depends only
 * on this package, never on the SDK's exception hierarchy.
 */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
