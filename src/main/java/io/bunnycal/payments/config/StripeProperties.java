package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe credentials and redirect URLs for the SaaS subscription billing module.
 *
 * <p>Bound from the {@code billing.stripe.*} block in application.yaml. Secrets are
 * injected from environment variables ({@code STRIPE_SECRET_KEY},
 * {@code STRIPE_WEBHOOK_SECRET}) — never checked in.
 */
@ConfigurationProperties(prefix = "billing.stripe")
public record StripeProperties(
        String secretKey,
        String webhookSecret,
        String successUrl,
        String cancelUrl,
        String portalReturnUrl) {
}
