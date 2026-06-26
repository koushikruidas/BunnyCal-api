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
        int trialDays,
        int graceDays,
        Notifications notifications) {

    public record Notifications(boolean enabled, String from) {
    }
}
