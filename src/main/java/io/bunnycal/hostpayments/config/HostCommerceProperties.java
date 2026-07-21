package io.bunnycal.hostpayments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "commerce")
public record HostCommerceProperties(
        boolean enabled,
        String frontendBaseUrl,
        Stripe stripe,
        Paypal paypal) {

    public HostCommerceProperties(boolean enabled, String frontendBaseUrl, Stripe stripe) {
        this(enabled, frontendBaseUrl, stripe, null);
    }

    public record Stripe(
            String secretKey,
            String publishableKey,
            String webhookSecret,
            String onboardingReturnUrl,
            String onboardingRefreshUrl) {
    }

    public record Paypal(boolean enabled, String clientId, String clientSecret,
                         String partnerMerchantId, String partnerAttributionId,
                         String webhookId, String onboardingReturnUrl, String apiBaseUrl) {
    }
}
