package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe credentials for the SaaS subscription billing module.
 *
 * <p>Bound from the {@code billing.stripe.*} block in application.yaml. Secrets are
 * injected from environment variables ({@code STRIPE_SECRET_KEY},
 * {@code STRIPE_WEBHOOK_SECRET}) — never checked in. Redirect URLs are provider-neutral
 * and live in {@link BillingRedirectProperties}.
 */
@ConfigurationProperties(prefix = "billing.stripe")
public record StripeProperties(
        String secretKey,
        String webhookSecret) {
}
