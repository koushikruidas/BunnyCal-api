package com.daedalussystems.easySchedule;

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
        "com.daedalussystems.easySchedule.booking",
        "com.daedalussystems.easySchedule.sync",
        "com.daedalussystems.easySchedule.calendar.service",
        "com.daedalussystems.easySchedule.calendar.client",
        "com.daedalussystems.easySchedule.calendar.provider",
        "com.daedalussystems.easySchedule.calendar.auth",
        "com.daedalussystems.easySchedule.calendar.config",
        "com.daedalussystems.easySchedule.calendar.repository",
        "com.daedalussystems.easySchedule.availability",
        "com.daedalussystems.easySchedule.common"
})
public class TestApplication {
    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
