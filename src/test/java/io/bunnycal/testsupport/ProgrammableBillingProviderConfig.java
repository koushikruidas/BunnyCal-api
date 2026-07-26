package io.bunnycal.testsupport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Registers a {@link ProgrammableBillingProvider} as the primary {@code PaymentProvider} for an
 * integration test. Lives in {@code io.bunnycal.testsupport}, which is deliberately NOT in
 * {@code TestApplication}'s component-scan list, so it applies only to tests that {@code @Import} it
 * — no leaking a second primary provider into sibling test contexts.
 */
@Configuration
public class ProgrammableBillingProviderConfig {

    @Bean
    @Primary
    public ProgrammableBillingProvider programmableBillingProvider() {
        return new ProgrammableBillingProvider();
    }
}
