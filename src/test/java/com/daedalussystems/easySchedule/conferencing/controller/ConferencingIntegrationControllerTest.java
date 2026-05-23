package com.daedalussystems.easySchedule.conferencing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.conferencing.service.ZoomConferencingOAuthService;
import com.daedalussystems.easySchedule.integration.ProviderAuthoritySummary;
import com.daedalussystems.easySchedule.integration.ProviderCatalogResponse;
import com.daedalussystems.easySchedule.integration.ProviderCatalogService;
import com.daedalussystems.easySchedule.integration.ProviderCapabilityRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class ConferencingIntegrationControllerTest {
    @Mock
    private ZoomConferencingOAuthService zoomOAuthService;
    @Mock
    private ProviderCatalogService providerCatalogService;

    private ConferencingIntegrationController controller;

    @BeforeEach
    void setUp() {
        controller = new ConferencingIntegrationController(
                zoomOAuthService,
                new ProviderCapabilityRegistry(),
                providerCatalogService,
                "http://localhost:3000/error",
                "http://localhost:3000/success");
    }

    @Test
    void status_includesLegacyAndCanonicalBlocks() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.status(userId)).thenReturn("CONNECTED");
        ProviderCatalogResponse response = new ProviderCatalogResponse(
                "v1alpha-provider-catalog",
                List.of(),
                new ProviderAuthoritySummary("google", List.of("google"), "google", List.of("zoom")));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(response);
        when(providerCatalogService.conferencingProviderSubset(userId)).thenReturn(Map.of());

        ApiResponse<Map<String, Object>> body = controller.status(authentication).getBody();

        assertEquals(true, body.isSuccess());
        assertNotNull(body.getData().get("providers"));
        assertNotNull(body.getData().get("capabilities"));
        assertNotNull(body.getData().get("providerCatalog"));
        assertNotNull(body.getData().get("authority"));
    }
}
