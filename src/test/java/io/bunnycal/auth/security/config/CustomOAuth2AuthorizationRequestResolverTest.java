package io.bunnycal.auth.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CustomOAuth2AuthorizationRequestResolverTest {
    private static final String GOOGLE_EVENTS = "https://www.googleapis.com/auth/calendar.events";
    private static final String GOOGLE_READ = "https://www.googleapis.com/auth/calendar.readonly";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void hostGoogleLoginAddsCalendarAndOfflineAccess() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        request.addParameter("calendar", "host");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes()).contains("email", "profile", GOOGLE_EVENTS, GOOGLE_READ);
        assertThat(authorization.getAdditionalParameters())
                .containsEntry("access_type", "offline")
                .containsEntry("include_granted_scopes", "true");
        assertThat(Arrays.stream(response.getCookies()))
                .anyMatch(cookie -> CustomOAuth2AuthorizationRequestResolver.HOST_CALENDAR_COOKIE
                        .equals(cookie.getName()) && "host".equals(cookie.getValue()));
    }

    @Test
    void guestGoogleLoginKeepsIdentityOnlyScopes() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes()).containsExactlyInAnyOrder("email", "profile");
        assertThat(authorization.getAdditionalParameters()).doesNotContainKeys(
                "access_type", "include_granted_scopes");
    }

    @Test
    void hostMicrosoftLoginAddsCalendarAndOfflineAccess() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("microsoft");
        MockHttpServletRequest request = oauthRequest("microsoft");
        request.addParameter("calendar", "host");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes()).contains(
                "offline_access", "Calendars.ReadWrite", "Calendars.Read");
    }

    private static CustomOAuth2AuthorizationRequestResolver resolver(String registrationId) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("email", "profile")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .userInfoUri("https://provider.example/userinfo")
                .userNameAttributeName("sub")
                .clientName(registrationId)
                .build();
        return new CustomOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration));
    }

    private static MockHttpServletRequest oauthRequest(String registrationId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/oauth2/authorization/" + registrationId);
        request.setServletPath("/oauth2/authorization/" + registrationId);
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setScheme("http");
        return request;
    }
}
