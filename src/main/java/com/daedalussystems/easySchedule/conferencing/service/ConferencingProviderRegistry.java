package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ConferencingProviderRegistry {
    private final Map<ConferencingProviderType, ConferencingProvider> byType;

    public ConferencingProviderRegistry(List<ConferencingProvider> providers) {
        EnumMap<ConferencingProviderType, ConferencingProvider> m = new EnumMap<>(ConferencingProviderType.class);
        for (ConferencingProvider provider : providers) {
            m.put(provider.providerType(), provider);
        }
        this.byType = Map.copyOf(m);
    }

    public ConferencingProvider resolve(ConferencingProviderType type) {
        ConferencingProvider provider = byType.get(type);
        if (provider == null) {
            throw new IllegalStateException("conferencing provider not configured: " + type);
        }
        return provider;
    }
}
