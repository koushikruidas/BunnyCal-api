package io.bunnycal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaAuditing
@EntityScan(basePackages = "io.bunnycal")
@EnableJpaRepositories(basePackages = "io.bunnycal")
public class BunnyCalApplication {

	public static void main(String[] args) {
		SpringApplication.run(BunnyCalApplication.class, args);
	}

}
