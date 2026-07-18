package io.bunnycal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class MailTransportConfigurationTest {

    @Test
    void port587DefaultsToAuthenticatedRequiredStartTls() throws IOException {
        ConfigurableEnvironment environment = loadApplicationConfiguration();

        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.auth", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable", Boolean.class)).isFalse();
        assertThat(environment.getProperty(
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
                Boolean.class)).isTrue();
    }

    @Test
    void transportCanBeSwitchedToImplicitTlsForPort465() throws IOException {
        ConfigurableEnvironment environment = loadApplicationConfiguration();
        environment.getPropertySources().addFirst(new MapPropertySource("implicit-tls", Map.of(
                "MAIL_SMTP_STARTTLS_ENABLE", "false",
                "MAIL_SMTP_STARTTLS_REQUIRED", "false",
                "MAIL_SMTP_SSL_ENABLE", "true")));

        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable", Boolean.class)).isTrue();
    }

    @Test
    void genericCredentialsTakePrecedenceAndLegacySesNamesRemainSupported() throws IOException {
        ConfigurableEnvironment genericEnvironment = loadApplicationConfiguration();
        genericEnvironment.getPropertySources().addFirst(new MapPropertySource("generic-credentials", Map.of(
                "SPRING_MAIL_USERNAME", "provider-user",
                "SPRING_MAIL_PASSWORD", "provider-password",
                "AWS_SES_SMTP_USERNAME", "legacy-user",
                "AWS_SES_SMTP_PASSWORD", "legacy-password")));

        assertThat(genericEnvironment.getProperty("spring.mail.username")).isEqualTo("provider-user");
        assertThat(genericEnvironment.getProperty("spring.mail.password")).isEqualTo("provider-password");

        ConfigurableEnvironment legacyEnvironment = loadApplicationConfiguration();
        legacyEnvironment.getPropertySources().addFirst(new MapPropertySource("legacy-credentials", Map.of(
                "AWS_SES_SMTP_USERNAME", "legacy-user",
                "AWS_SES_SMTP_PASSWORD", "legacy-password")));

        assertThat(legacyEnvironment.getProperty("spring.mail.username")).isEqualTo("legacy-user");
        assertThat(legacyEnvironment.getProperty("spring.mail.password")).isEqualTo("legacy-password");
    }

    private ConfigurableEnvironment loadApplicationConfiguration() throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
