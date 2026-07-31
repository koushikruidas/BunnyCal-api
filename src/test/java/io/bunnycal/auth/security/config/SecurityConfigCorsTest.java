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

    /**
     * Staging is a frontend-only preview that talks to the production API, so
     * stage.bunnycal.io has to be an accepted origin alongside the live site.
     * It was missing, and every request from staging failed the preflight with
     * "Invalid CORS request".
     *
     * This pins the comma-separated parsing as well: a single trailing space or
     * a swap to a non-splitting property type would silently collapse the list
     * back to one origin and take staging down again.
     */
    @Test
    void customerApiAllowsBothProductionAndStagingOrigins() {
        SecurityConfig config = new SecurityConfig(
                mock(CustomOAuth2UserService.class),
                mock(OAuth2AuthenticationSuccessHandler.class),
                mock(JwtAuthenticationFilter.class),
                mock(OAuth2AuthorizationRequestResolver.class));
        ReflectionTestUtils.setField(
                config, "allowedOrigins", "https://bunnycal.io, https://stage.bunnycal.io");
        ReflectionTestUtils.setField(config, "adminAllowedOrigins", "https://admin.bunnycal.io");
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");

        CorsConfiguration cors = source.getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        // Whitespace around the comma must be trimmed, or the pattern never matches.
        assertThat(cors.getAllowedOriginPatterns())
                .containsExactly("https://bunnycal.io", "https://stage.bunnycal.io");
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    /**
     * The admin allowlist is deliberately separate: a customer origin must never
     * be able to send credentialed requests to /api/admin/**, so widening the
     * customer list (as staging did) must not widen this one.
     */
    @Test
    void adminApiDoesNotInheritCustomerOrigins() {
        SecurityConfig config = new SecurityConfig(
                mock(CustomOAuth2UserService.class),
                mock(OAuth2AuthenticationSuccessHandler.class),
                mock(JwtAuthenticationFilter.class),
                mock(OAuth2AuthorizationRequestResolver.class));
        ReflectionTestUtils.setField(
                config, "allowedOrigins", "https://bunnycal.io,https://stage.bunnycal.io");
        ReflectionTestUtils.setField(config, "adminAllowedOrigins", "https://admin.bunnycal.io");
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");

        CorsConfiguration cors = source.getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOriginPatterns()).containsExactly("https://admin.bunnycal.io");
        assertThat(cors.getAllowedOriginPatterns()).doesNotContain("https://stage.bunnycal.io");
    }
}
