package io.bunnycal.calendar.service;

import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CalendarWebhookStartupDiagnostics implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CalendarWebhookStartupDiagnostics.class);

    private final CalendarWebhookProperties webhookProperties;

    public CalendarWebhookStartupDiagnostics(CalendarWebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean googleEnabled = webhookProperties.isProviderWebhookEnabled(CalendarProviderType.GOOGLE);
        boolean microsoftEnabled = webhookProperties.isProviderWebhookEnabled(CalendarProviderType.MICROSOFT);
        boolean webhookPlusPull = googleEnabled || microsoftEnabled;
        String mode = webhookPlusPull ? "WEBHOOK_PLUS_PULL" : "PULL_ONLY";

        log.info("calendar_sync_mode={} google_webhooks={} microsoft_webhooks={}",
                mode,
                googleEnabled ? "enabled" : "disabled",
                microsoftEnabled ? "enabled" : "disabled");

        validateProviderAddress("google", googleEnabled, webhookProperties.getProvider().getGoogle().getAddress());
        validateProviderAddress("microsoft", microsoftEnabled, webhookProperties.getProvider().getMicrosoft().getAddress());
    }

    private void validateProviderAddress(String provider, boolean enabled, String address) {
        if (!enabled) {
            return;
        }
        if (address == null || address.isBlank()) {
            log.warn("{} webhooks enabled but webhook URL is empty. Subscription/watch creation will be skipped or fail.",
                    providerLabel(provider));
            return;
        }

        URI uri;
        try {
            uri = URI.create(address);
        } catch (RuntimeException ex) {
            log.warn("{} webhooks enabled but webhook URL is invalid: {}. Subscription/watch creation may fail.",
                    providerLabel(provider), address);
            return;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean localhostLike = host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.endsWith(".local");
        boolean https = scheme.equals("https");

        if ("microsoft".equals(provider) && !https) {
            log.warn("Microsoft webhooks enabled but configured webhook URL is not HTTPS. Graph subscription creation will fail. url={}",
                    address);
        }
        if (localhostLike || !https) {
            log.warn("{} webhooks enabled but webhook URL appears localhost/non-public or non-HTTPS. Watch/subscription creation may fail. url={}",
                    providerLabel(provider), address);
        }
    }

    private static String providerLabel(String provider) {
        return provider == null || provider.isBlank()
                ? "Unknown provider"
                : Character.toUpperCase(provider.charAt(0)) + provider.substring(1);
    }
}
