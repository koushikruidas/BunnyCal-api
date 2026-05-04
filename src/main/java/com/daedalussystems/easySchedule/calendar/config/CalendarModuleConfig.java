package com.daedalussystems.easySchedule.calendar.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        CalendarSecurityProperties.class,
        GoogleOAuthProperties.class
})
public class CalendarModuleConfig {
}
