package com.daedalussystems.easySchedule;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaAuditing
@ComponentScan(basePackages = {
        "com.daedalussystems.easySchedule.booking",
        "com.daedalussystems.easySchedule.sync",
        "com.daedalussystems.easySchedule.calendar",
        "com.daedalussystems.easySchedule.availability",
        "com.daedalussystems.easySchedule.common"
})
public class TestApplication {
}
