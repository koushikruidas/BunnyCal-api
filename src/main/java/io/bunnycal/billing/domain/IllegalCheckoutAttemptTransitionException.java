package io.bunnycal.billing.domain;

/**
 * Thrown when a {@link CheckoutAttempt} is asked to make a transition its lifecycle forbids
 * (e.g. reopening a SUCCEEDED attempt through the ordinary path). A terminal attempt may only be
 * un-terminalized via {@link CheckoutAttempt#recoverToSucceeded}.
 */
public class IllegalCheckoutAttemptTransitionException extends RuntimeException {

    public IllegalCheckoutAttemptTransitionException(CheckoutAttemptStatus from, CheckoutAttemptStatus to) {
        super("Illegal checkout attempt transition " + from + " -> " + to);
    }
}
