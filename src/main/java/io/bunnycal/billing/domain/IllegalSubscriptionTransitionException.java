package io.bunnycal.billing.domain;

/**
 * Thrown when a subscription is asked to make a state transition its lifecycle does not allow
 * (e.g. reactivating a terminal CANCELLED/EXPIRED/REFUNDED subscription). Signals a domain
 * invariant violation — callers should treat it as a bug or a stale reconciliation, not retry it.
 */
public class IllegalSubscriptionTransitionException extends RuntimeException {

    public IllegalSubscriptionTransitionException(SubscriptionStatus from, SubscriptionStatus to) {
        super("Illegal subscription transition " + from + " -> " + to);
    }
}
