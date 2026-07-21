package io.bunnycal.hostpayments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "commerce")
public record HostCommerceProperties(
        boolean enabled,
        String frontendBaseUrl,
        Stripe stripe) {

    public record Stripe(
            String secretKey,
            String publishableKey,
            String webhookSecret,
            String onboardingReturnUrl,
            String onboardingRefreshUrl) {
    }
}
