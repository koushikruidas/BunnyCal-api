package io.bunnycal.auth.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.bunnycal.auth.oauth.handler.OAuth2AuthenticationSuccessHandler;
import io.bunnycal.auth.oauth.service.CustomOAuth2UserService;
import io.bunnycal.auth.security.filter.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class SecurityConfigCorsTest {

    @Test
    void customerApiAllowsPatchForOnboardingProgress() {
        SecurityConfig config = new SecurityConfig(
                mock(CustomOAuth2UserService.class),
                mock(OAuth2AuthenticationSuccessHandler.class),
                mock(JwtAuthenticationFilter.class),
                mock(OAuth2AuthorizationRequestResolver.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
        ReflectionTestUtils.setField(config, "adminAllowedOrigins", "http://localhost:5174");
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/onboarding");

        CorsConfiguration cors = source.getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedMethods()).contains("PATCH");
    }
}
