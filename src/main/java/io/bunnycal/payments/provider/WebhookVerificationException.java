package io.bunnycal.payments.provider;

/**
 * Thrown when an inbound webhook payload fails provider signature verification.
 * The webhook controller maps this to HTTP 400 and persists nothing.
 */
public class WebhookVerificationException extends RuntimeException {

    public WebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
