package io.bunnycal.auth.security.config;

import io.bunnycal.auth.oauth.handler.OAuth2AuthenticationSuccessHandler;
import io.bunnycal.auth.oauth.service.CustomOAuth2UserService;
import io.bunnycal.auth.security.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "security.enabled",
        havingValue = "true",
        matchIfMissing = true // ENABLED by default (production-safe)
)
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2AuthorizationRequestResolver customResolver;
    @Value("${app.security.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;
    @Value("${app.security.cors.admin-allowed-origins:http://localhost:5174}")
    private String adminAllowedOrigins;

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();

        // Public embed endpoints accept any origin (served into third-party iframes).
        // No credentials — embed tokens are in the request body, not cookies.
        org.springframework.web.cors.CorsConfiguration embedConfig = new org.springframework.web.cors.CorsConfiguration();
        embedConfig.setAllowedOriginPatterns(List.of("*"));
        embedConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        embedConfig.setAllowedHeaders(List.of("*"));
        embedConfig.setAllowCredentials(false);
        source.registerCorsConfiguration("/public/embed/**", embedConfig);

        // Admin API: only the admin origin (admin.bunnycal.io) may send credentialed requests.
        // Registered before the /** rule so it takes precedence for /api/admin/**. Combined with
        // the ROLE_* check, a customer-origin request can never reach admin endpoints.
        org.springframework.web.cors.CorsConfiguration adminConfig = new org.springframework.web.cors.CorsConfiguration();
        adminConfig.setAllowedOriginPatterns(parseOrigins(adminAllowedOrigins));
        adminConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        adminConfig.setAllowedHeaders(List.of("*"));
        adminConfig.setAllowCredentials(true);
        source.registerCorsConfiguration("/api/admin/**", adminConfig);

        // All other paths: allow only configured origins with credentials (dashboard, booking page).
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(parseOrigins(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    private static List<String> parseOrigins(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");

                            response.getWriter().write("""
                    {
                      "success": false,
                      "error": {
                        "code": "UNAUTHORIZED",
                        "message": "Authentication required"
                      }
                    }
                """);
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json");

                            response.getWriter().write("""
                    {
                      "success": false,
                      "error": {
                        "code": "FORBIDDEN",
                        "message": "Access denied"
                      }
                    }
                """);
                        })
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/oauth2/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/auth/providers", "/auth/refresh").permitAll()
                        .requestMatchers("/auth/logout", "/auth/session").authenticated()
                        .requestMatchers("/integrations/calendar/google/callback").permitAll()
                        .requestMatchers("/integrations/calendar/*/callback").permitAll()
                        .requestMatchers("/integrations/conferencing/*/callback").permitAll()
                        .requestMatchers("/integrations/calendar/webhooks/**").permitAll()
                        // Zoom marketplace webhooks (deauthorization) authenticate via
                        // x-zm-signature HMAC, not JWT.
                        .requestMatchers("/integrations/conferencing/zoom/webhooks").permitAll()
                        .requestMatchers("/integrations/calendar/**").authenticated()
                        .requestMatchers("/integrations/conferencing/**").authenticated()
                        // Customer-facing announcement banner. Optional JWT may still populate
                        // Authentication so the service can resolve FREE vs PAID audience.
                        .requestMatchers("/api/announcements/active").permitAll()
                        // Provider webhooks authenticate via signature, not JWT. Must precede /api/**.
                        .requestMatchers("/api/billing/webhooks/**").permitAll()
                        // Admin API requires an admin role. Must precede the generic /api/** rule.
                        .requestMatchers("/api/admin/**")
                            .hasAnyRole("ADMIN", "SUPER_ADMIN", "SUPPORT", "FINANCE", "OPERATIONS")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )

                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(customResolver)
                        )
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
