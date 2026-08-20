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

    /**
     * Host sign-in asks for identity and calendar in one authorization, so a new host consents
     * once and arrives with a usable calendar.
     *
     * <p>{@code prompt} is absent, which is what makes that possible: Google shows the consent
     * screen only for scopes the user has not already granted, so a returning host sees nothing.
     * Sending {@code prompt=consent} would re-prompt on every sign-in; verified against Google on
     * 2026-08-20 across a first signup, a repeat sign-in, and a re-signup after a database wipe.
     */
    @Test
    void hostGoogleLoginRequestsIdentityAndCalendarWithoutForcingConsent() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        request.addParameter("calendar", "host");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes())
                .containsExactlyInAnyOrder("email", "profile", GOOGLE_EVENTS, GOOGLE_READ);
        // access_type=offline asks for the refresh token that background sync needs. Google
        // returns one on a user's first authorization only, which is enough:
        // CalendarOAuthService carries the stored one forward when a later callback omits it.
        assertThat(authorization.getAdditionalParameters()).containsEntry("access_type", "offline");
        // Any prompt value would defeat the point — consent re-prompts every time, and
        // select_account shows the picker on every sign-in.
        assertThat(authorization.getAdditionalParameters()).doesNotContainKey("prompt");
    }

    /** Microsoft sign-in is unchanged: its calendar connection keeps its own dedicated flow. */
    @Test
    void hostMicrosoftLoginRequestsIdentityOnly() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("microsoft");
        MockHttpServletRequest request = oauthRequest("microsoft");
        request.addParameter("calendar", "host");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes())
                .doesNotContain("offline_access", "Calendars.ReadWrite", "Calendars.Read");
    }

    /**
     * Calendar scopes are gated on the host marker rather than declared on the client
     * registration. On the registration they applied to EVERY Google authorization, so admin
     * login asked for calendar access it never uses — an over-broad request that also invites
     * scrutiny of the app's verification standing.
     */
    @Test
    void adminLoginNeverAsksForCalendarAccess() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        request.addParameter("client", "admin");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes()).containsExactlyInAnyOrder("email", "profile");
        assertThat(authorization.getScopes()).doesNotContain(GOOGLE_EVENTS, GOOGLE_READ);
        assertThat(authorization.getAdditionalParameters()).doesNotContainKey("access_type");
    }

    @Test
    void loginWithoutTheHostMarkerKeepsIdentityOnlyScopes() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes()).containsExactlyInAnyOrder("email", "profile");
        assertThat(authorization.getScopes()).doesNotContain(GOOGLE_EVENTS, GOOGLE_READ);
        assertThat(authorization.getAdditionalParameters()).doesNotContainKeys(
                "access_type", "include_granted_scopes");
    }

    /** The success handler bootstraps a calendar only when this cookie says the intent was host. */
    @Test
    void theHostMarkerIsRememberedAcrossTheProviderRedirect() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        request.addParameter("calendar", "host");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        resolver.resolve(request);

        jakarta.servlet.http.Cookie cookie = response.getCookie(
                CustomOAuth2AuthorizationRequestResolver.HOST_CALENDAR_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("host");
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    /** Without the marker the cookie is actively cleared, so a stale one cannot arm a bootstrap. */
    @Test
    void aStaleHostMarkerIsClearedWhenTheIntentIsAbsent() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        resolver.resolve(request);

        jakarta.servlet.http.Cookie cookie = response.getCookie(
                CustomOAuth2AuthorizationRequestResolver.HOST_CALENDAR_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isZero();
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
