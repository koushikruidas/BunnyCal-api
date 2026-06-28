package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dodo Payments credentials for the SaaS subscription billing module (Merchant-of-Record mode).
 *
 * <p>Bound from the {@code billing.dodo.*} block in application.yaml. Secrets are injected from
 * environment variables ({@code DODO_API_KEY}, {@code DODO_WEBHOOK_SECRET}) — never checked in.
 *
 * <p>{@code testMode} selects Dodo's test environment vs. live. Redirect URLs are shared across
 * providers and live in {@link BillingRedirectProperties}.
 */
@ConfigurationProperties(prefix = "billing.dodo")
public record DodoProperties(
        String apiKey,
        /** Standard-Webhooks signing secret used to verify {@code webhook-signature}. */
        String webhookSecret,
        /** When true, the SDK targets Dodo's test environment. */
        boolean testMode) {
}
