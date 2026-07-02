package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-neutral redirect URLs for hosted Checkout / Customer Portal.
 *
 * <p>Bound from {@code billing.redirect.*}. These are where the provider (Stripe, Dodo, …)
 * sends the user back after a hosted flow, and are identical regardless of provider — so they
 * live here rather than inside any one provider's properties. The same defaults the Stripe
 * block used previously are preserved.
 */
@ConfigurationProperties(prefix = "billing.redirect")
public record BillingRedirectProperties(
        String successUrl,
        String cancelUrl,
        String portalReturnUrl) {
}
