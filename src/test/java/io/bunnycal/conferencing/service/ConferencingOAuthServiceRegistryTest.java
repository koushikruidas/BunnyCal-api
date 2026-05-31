package io.bunnycal.conferencing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.common.enums.ConferencingProviderType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConferencingOAuthServiceRegistryTest {

    @Test
    void resolvesByCanonicalEnumAndExternalIdentifier() {
        StubService zoom = new StubService(ConferencingProviderType.ZOOM);
        StubService meet = new StubService(ConferencingProviderType.GOOGLE_MEET);
        ConferencingOAuthServiceRegistry registry =
                new ConferencingOAuthServiceRegistry(List.of(zoom, meet));

        assertSame(zoom, registry.find("zoom").orElseThrow());
        assertSame(zoom, registry.find("ZOOM").orElseThrow());
        assertSame(meet, registry.find("google_meet").orElseThrow());
        assertSame(meet, registry.find("google-meet").orElseThrow());
        assertSame(meet, registry.find("Google-Meet").orElseThrow());
        assertSame(meet, registry.find(ConferencingProviderType.GOOGLE_MEET).orElseThrow());
    }

    @Test
    void unknownProviderResolvesToEmpty() {
        ConferencingOAuthServiceRegistry registry =
                new ConferencingOAuthServiceRegistry(List.of(new StubService(ConferencingProviderType.ZOOM)));

        assertTrue(registry.find("skype").isEmpty());
        assertTrue(registry.find((String) null).isEmpty());
        assertTrue(registry.find("google_meet").isEmpty(), "registry has no google_meet bean");
    }

    @Test
    void allReturnsCompleteMap() {
        StubService zoom = new StubService(ConferencingProviderType.ZOOM);
        StubService meet = new StubService(ConferencingProviderType.GOOGLE_MEET);
        ConferencingOAuthServiceRegistry registry =
                new ConferencingOAuthServiceRegistry(List.of(zoom, meet));

        assertEquals(2, registry.all().size());
        assertSame(zoom, registry.all().get(ConferencingProviderType.ZOOM));
        assertSame(meet, registry.all().get(ConferencingProviderType.GOOGLE_MEET));
    }

    private static final class StubService implements ConferencingOAuthService {
        private final ConferencingProviderType type;

        StubService(ConferencingProviderType type) {
            this.type = type;
        }

        @Override
        public ConferencingProviderType providerType() {
            return type;
        }

        @Override
        public ConferencingProviderCapabilities capabilities() {
            return ConferencingProviderCapabilities.standalone();
        }

        @Override
        public String buildConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
            return "https://stub/" + type.externalId();
        }

        @Override
        public String status(UUID userId) {
            return "NOT_CONNECTED";
        }

        @Override
        public void disconnect(UUID userId) {
        }
    }
}
