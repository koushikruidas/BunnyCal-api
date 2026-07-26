package io.bunnycal.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure state-machine tests for the guarded {@link Subscription} transitions. */
class SubscriptionTransitionTest {

    private static Subscription withStatus(SubscriptionStatus status) {
        return Subscription.builder().status(status).build();
    }

    @Test
    void activate_fromIncomplete_becomesActive() {
        Subscription s = withStatus(SubscriptionStatus.INCOMPLETE);
        s.activate();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void activate_fromActive_isIdempotent() {
        Subscription s = withStatus(SubscriptionStatus.ACTIVE);
        s.activate();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void activate_fromTerminal_throws() {
        for (SubscriptionStatus terminal : SubscriptionStatus.TERMINAL) {
            Subscription s = withStatus(terminal);
            assertThatThrownBy(s::activate)
                    .isInstanceOf(IllegalSubscriptionTransitionException.class);
        }
    }

    @Test
    void markPastDue_fromActive_ok_butNotFromTrial() {
        Subscription active = withStatus(SubscriptionStatus.ACTIVE);
        active.markPastDue();
        assertThat(active.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);

        Subscription trial = withStatus(SubscriptionStatus.TRIAL);
        assertThatThrownBy(trial::markPastDue)
                .isInstanceOf(IllegalSubscriptionTransitionException.class);
    }

    @Test
    void cancel_fromNonTerminal_ok_idempotentFromCancelled_throwsFromOtherTerminal() {
        Subscription active = withStatus(SubscriptionStatus.ACTIVE);
        active.cancel();
        assertThat(active.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);

        Subscription cancelled = withStatus(SubscriptionStatus.CANCELLED);
        cancelled.cancel(); // idempotent, no throw
        assertThat(cancelled.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);

        Subscription refunded = withStatus(SubscriptionStatus.REFUNDED);
        assertThatThrownBy(refunded::cancel)
                .isInstanceOf(IllegalSubscriptionTransitionException.class);
    }

    @Test
    void expire_fromTrial_ok_butNotFromCancelled() {
        Subscription trial = withStatus(SubscriptionStatus.TRIAL);
        trial.expire();
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);

        Subscription cancelled = withStatus(SubscriptionStatus.CANCELLED);
        assertThatThrownBy(cancelled::expire)
                .isInstanceOf(IllegalSubscriptionTransitionException.class);
    }

    @Test
    void checkoutAttempt_recoverToSucceeded_onlyFromTerminalFailedOrExpired() {
        CheckoutAttempt failed = CheckoutAttempt.builder().status(CheckoutAttemptStatus.FAILED).build();
        failed.recoverToSucceeded(Instant.now());
        assertThat(failed.getStatus()).isEqualTo(CheckoutAttemptStatus.SUCCEEDED);

        CheckoutAttempt open = CheckoutAttempt.builder().status(CheckoutAttemptStatus.OPEN).build();
        assertThatThrownBy(() -> open.recoverToSucceeded(Instant.now()))
                .isInstanceOf(IllegalCheckoutAttemptTransitionException.class);
    }

    @Test
    void checkoutAttempt_happyPath_createdToSucceeded() {
        CheckoutAttempt a = CheckoutAttempt.builder().status(CheckoutAttemptStatus.CREATED).build();
        a.markRedirected(Instant.now());
        assertThat(a.getStatus()).isEqualTo(CheckoutAttemptStatus.OPEN);
        a.markProcessing();
        assertThat(a.getStatus()).isEqualTo(CheckoutAttemptStatus.PROCESSING);
        a.succeed(Instant.now());
        assertThat(a.getStatus()).isEqualTo(CheckoutAttemptStatus.SUCCEEDED);
    }
}
