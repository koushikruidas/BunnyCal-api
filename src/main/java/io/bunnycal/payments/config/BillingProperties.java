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
        Fees fees) {

    public BillingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stripe";
        }
        if (fees == null) {
            fees = new Fees(0);
        }
    }

    public record Notifications(boolean enabled, String from) {
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
