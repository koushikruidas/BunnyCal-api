package io.bunnycal;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestClient;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaAuditing
@ComponentScan(basePackages = {
        "io.bunnycal.booking",
        "io.bunnycal.session",
        "io.bunnycal.sync",
        "io.bunnycal.calendar.service",
        "io.bunnycal.calendar.client",
        "io.bunnycal.calendar.provider",
        "io.bunnycal.calendar.auth",
        "io.bunnycal.calendar.config",
        "io.bunnycal.calendar.repository",
        "io.bunnycal.availability",
        "io.bunnycal.conferencing",
        "io.bunnycal.integration",
        "io.bunnycal.team",
        "io.bunnycal.payments",
        "io.bunnycal.billing",
        "io.bunnycal.common",
        "io.bunnycal.auth.service",
        "io.bunnycal.auth.account",
        "io.bunnycal.auth.avatar"
})
public class TestApplication {
    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
