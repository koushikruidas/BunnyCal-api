package io.bunnycal.calendar.config;

import io.bunnycal.calendar.domain.CalendarProviderType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "calendar.webhook")
@Getter
@Setter
public class CalendarWebhookProperties {
    private boolean enabled = true;
    private String sharedSecret = "";
    private final Provider provider = new Provider();

    public boolean isProviderWebhookEnabled(CalendarProviderType providerType) {
        if (!enabled || providerType == null) {
            return false;
        }
        return switch (providerType) {
            case GOOGLE -> provider.google.enabled;
            case MICROSOFT -> provider.microsoft.enabled;
        };
    }

    @Getter
    @Setter
    public static class Provider {
        private final Google google = new Google();
        private final Microsoft microsoft = new Microsoft();
    }

    @Getter
    @Setter
    public static class Google {
        private boolean enabled = true;
        private String address = "";
    }

    @Getter
    @Setter
    public static class Microsoft {
        private boolean enabled = true;
        private String address = "";
        private long ttlSeconds = 7200L;
    }
}
