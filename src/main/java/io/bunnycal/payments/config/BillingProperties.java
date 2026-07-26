package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level billing module settings (independent of the payment provider).
 *
 * <p>Bound from the {@code billing.*} block in application.yaml.
 */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
        boolean enabled,
        /**
         * Which payment provider is active: {@code stripe} (direct merchant) or {@code dodo}
         * (Merchant of Record). Selects the {@link io.bunnycal.payments.provider.PaymentProvider}
         * bean via {@code @ConditionalOnProperty}. Defaults to {@code stripe}.
         */
        String provider,
        int graceDays,
        Notifications notifications,
        Fees fees,
        Reconcile reconcile) {

    public BillingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stripe";
        }
        if (fees == null) {
            fees = new Fees(0);
        }
        if (reconcile == null) {
            reconcile = new Reconcile(0, 0, 0);
        }
    }

    public record Notifications(boolean enabled, String from) {
    }

    /**
     * Deferred-webhook reconciliation cron tuning. {@code stalenessMinutes} — how long since the
     * last provider observation before a non-terminal subscription is re-read (default 60).
     * {@code checkoutStaleMinutes} — how old an open checkout attempt must be before the cron tries
     * to resolve/expire it (default 30). {@code batchSize} — max subscriptions re-read per run
     * (default 100). Zero/negative falls back to the default.
     */
    public record Reconcile(int stalenessMinutes, int checkoutStaleMinutes, int batchSize) {
        public Reconcile {
            if (stalenessMinutes <= 0) {
                stalenessMinutes = 60;
            }
            if (checkoutStaleMinutes <= 0) {
                checkoutStaleMinutes = 30;
            }
            if (batchSize <= 0) {
                batchSize = 100;
            }
        }
    }

    /**
     * Provider fee assumptions for revenue reporting. {@code processorPercentBps} is the
     * processor/MoR cut in basis points (e.g. 500 = 5%). The admin Revenue report uses it to
     * <em>estimate</em> fees and net, clearly labeled as an estimate — actual per-transaction
     * fees are not persisted. {@code 0} (default) means "not configured": the UI then shows
     * fees/net as unavailable rather than a misleading zero.
     */
    public record Fees(int processorPercentBps) {
    }
}
