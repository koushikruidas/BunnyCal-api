package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Registry that resolves a {@link ConferencingOAuthService} from the canonical
 * {@link ConferencingProviderType} or from a raw provider identifier supplied by
 * the frontend (path variable). Normalization is delegated to
 * {@link ConferencingProviderType#fromExternal(String)} so the wire format
 * ({@code zoom}, {@code google_meet}, {@code GOOGLE-MEET}, ...) is decoded in
 * exactly one place.
 */
@Component
public class ConferencingOAuthServiceRegistry {
    private final Map<ConferencingProviderType, ConferencingOAuthService> services;

    public ConferencingOAuthServiceRegistry(List<ConferencingOAuthService> services) {
        EnumMap<ConferencingProviderType, ConferencingOAuthService> byType =
                new EnumMap<>(ConferencingProviderType.class);
        for (ConferencingOAuthService service : services) {
            byType.put(service.providerType(), service);
        }
        this.services = Map.copyOf(byType);
    }

    public Optional<ConferencingOAuthService> find(String rawProvider) {
        return ConferencingProviderType.fromExternal(rawProvider).flatMap(this::find);
    }

    public Optional<ConferencingOAuthService> find(ConferencingProviderType type) {
        return Optional.ofNullable(services.get(type));
    }

    public Map<ConferencingProviderType, ConferencingOAuthService> all() {
        return services;
    }
}
