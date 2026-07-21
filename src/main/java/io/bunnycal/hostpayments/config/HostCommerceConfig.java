package io.bunnycal.hostpayments.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HostCommerceProperties.class)
public class HostCommerceConfig {
}
