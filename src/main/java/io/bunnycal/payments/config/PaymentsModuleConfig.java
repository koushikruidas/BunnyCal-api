package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers billing/payments configuration properties. Mirrors
 * {@code CalendarModuleConfig}. Property binding is always active; the beans that
 * actually talk to Stripe are gated by {@code billing.enabled}.
 */
@Configuration
@EnableConfigurationProperties({
        BillingProperties.class,
        BillingRedirectProperties.class,
        InvoicePresentationProperties.class,
        StripeProperties.class,
        DodoProperties.class
})
public class PaymentsModuleConfig {
}
