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
        int trialDays,
        int graceDays,
        Notifications notifications) {

    public BillingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stripe";
        }
    }

    public record Notifications(boolean enabled, String from) {
    }
}
