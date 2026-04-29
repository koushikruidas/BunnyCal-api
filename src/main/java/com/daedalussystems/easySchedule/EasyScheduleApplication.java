package com.daedalussystems.easySchedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaAuditing
@EntityScan(basePackages = "com.daedalussystems")
@EnableJpaRepositories(basePackages = "com.daedalussystems")
public class EasyScheduleApplication {

	public static void main(String[] args) {
		SpringApplication.run(EasyScheduleApplication.class, args);
	}

}
