package io.bunnycal.calendar.client;

import io.bunnycal.calendar.domain.CalendarProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CalendarProviderClientRegistry {
    private final Map<CalendarProviderType, CalendarProviderClient> clientsByProvider;

    public CalendarProviderClientRegistry(List<CalendarProviderClient> clients) {
        Map<CalendarProviderType, CalendarProviderClient> map = new EnumMap<>(CalendarProviderType.class);
        for (CalendarProviderClient client : clients) {
            CalendarProviderType provider = client.providerType();
            if (provider == null) {
                throw new IllegalStateException(
                        "CalendarProviderClient " + client.getClass().getName() + " declares a null provider");
            }
            CalendarProviderClient previous = map.putIfAbsent(provider, client);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate CalendarProviderClient for provider " + provider
                                + ": " + previous.getClass().getName() + " vs " + client.getClass().getName());
            }
        }
        this.clientsByProvider = Map.copyOf(map);
    }

    public CalendarProviderClient clientFor(CalendarProviderType provider) {
        if (provider == null) {
            throw new CalendarClientException(400, "provider is required to resolve calendar provider client");
        }
        CalendarProviderClient client = clientsByProvider.get(provider);
        if (client == null) {
            throw new CalendarClientException(400, "Unsupported calendar provider: " + provider);
        }
        return client;
    }

    public CalendarProviderClient clientFor(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new CalendarClientException(400, "provider is required to resolve calendar provider client");
        }
        try {
            return clientFor(CalendarProviderType.valueOf(provider.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            throw new CalendarClientException(400, "Unsupported calendar provider: " + provider);
        }
    }
}
