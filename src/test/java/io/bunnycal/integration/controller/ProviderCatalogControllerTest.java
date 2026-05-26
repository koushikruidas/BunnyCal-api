package io.bunnycal.integration.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.integration.ProviderAuthoritySummary;
import io.bunnycal.integration.ProviderCatalogResponse;
import io.bunnycal.integration.ProviderCatalogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class ProviderCatalogControllerTest {
    @Mock
    private ProviderCatalogService providerCatalogService;

    private ProviderCatalogController controller;

    @BeforeEach
    void setUp() {
        controller = new ProviderCatalogController(providerCatalogService);
    }

    @Test
    void catalog_returnsCanonicalPayload() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        ProviderCatalogResponse response = new ProviderCatalogResponse(
                "v1alpha-provider-catalog",
                List.of(),
                new ProviderAuthoritySummary("google", List.of("google"), "application", List.of("zoom")));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(response);

        ApiResponse<ProviderCatalogResponse> body = controller.catalog(authentication).getBody();

        assertEquals(true, body.isSuccess());
        assertNotNull(body.getData());
        assertEquals("v1alpha-provider-catalog", body.getData().version());
        assertEquals("google", body.getData().authority().identityProvider());
    }
}
