package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CalendarTokenClientRegistry {
    private final Map<CalendarProviderType, CalendarTokenClient> clientsByProvider;

    public CalendarTokenClientRegistry(List<CalendarTokenClient> clients) {
        Map<CalendarProviderType, CalendarTokenClient> map = new EnumMap<>(CalendarProviderType.class);
        for (CalendarTokenClient client : clients) {
            CalendarProviderType provider = client.provider();
            if (provider == null) {
                throw new IllegalStateException(
                        "CalendarTokenClient " + client.getClass().getName() + " declares a null provider");
            }
            CalendarTokenClient previous = map.putIfAbsent(provider, client);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate CalendarTokenClient for provider " + provider
                                + ": " + previous.getClass().getName() + " vs " + client.getClass().getName());
            }
        }
        this.clientsByProvider = Map.copyOf(map);
    }

    public CalendarTokenClient clientFor(CalendarProviderType provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required to resolve calendar token client");
        }
        CalendarTokenClient client = clientsByProvider.get(provider);
        if (client == null) {
            throw new IllegalStateException("No CalendarTokenClient registered for provider " + provider);
        }
        return client;
    }
}
