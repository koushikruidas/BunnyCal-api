package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CalendarSyncClientRegistry {
    private final Map<CalendarProviderType, ExternalCalendarSyncClient> clientsByProvider;

    public CalendarSyncClientRegistry(List<ExternalCalendarSyncClient> clients) {
        Map<CalendarProviderType, ExternalCalendarSyncClient> map = new EnumMap<>(CalendarProviderType.class);
        for (ExternalCalendarSyncClient client : clients) {
            CalendarProviderType provider = client.provider();
            if (provider == null) {
                throw new IllegalStateException(
                        "ExternalCalendarSyncClient " + client.getClass().getName() + " declares a null provider");
            }
            ExternalCalendarSyncClient previous = map.putIfAbsent(provider, client);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate ExternalCalendarSyncClient for provider " + provider
                                + ": " + previous.getClass().getName() + " vs " + client.getClass().getName());
            }
        }
        this.clientsByProvider = Map.copyOf(map);
    }

    public ExternalCalendarSyncClient clientFor(CalendarConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection is required to resolve calendar sync client");
        }
        return clientFor(connection.getProvider());
    }

    public ExternalCalendarSyncClient clientFor(CalendarProviderType provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required to resolve calendar sync client");
        }
        ExternalCalendarSyncClient client = clientsByProvider.get(provider);
        if (client == null) {
            throw new IllegalStateException("No ExternalCalendarSyncClient registered for provider " + provider);
        }
        return client;
    }
}
