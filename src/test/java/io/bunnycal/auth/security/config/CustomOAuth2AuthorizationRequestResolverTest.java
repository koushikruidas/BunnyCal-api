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
     * Sign-in requests identity only — even when the caller asks for a host calendar login.
     *
     * <p>Calendar scopes were bundled here, which forced this request to carry prompt=consent
     * (the only way Google returns a refresh token). Google re-shows its permission screen every
     * time that is sent, including for already-granted scopes, so every returning host
     * re-consented. Calendar access is now granted once at the CALENDAR onboarding step.
     */
    @Test
    void hostGoogleLoginRequestsIdentityOnlyAndNeverForcesConsent() {
        CustomOAuth2AuthorizationRequestResolver resolver = resolver("google");
        MockHttpServletRequest request = oauthRequest("google");
        request.addParameter("calendar", "host");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getScopes()).containsExactlyInAnyOrder("email", "profile");
        assertThat(authorization.getScopes()).doesNotContain(GOOGLE_EVENTS, GOOGLE_READ);
        // The account picker is fine — it never shows the permission screen. access_type and
        // consent are what would, and neither belongs on a sign-in.
        assertThat(authorization.getAdditionalParameters())
                .containsEntry("prompt", "select_account")
                .doesNotContainKeys("access_type", "include_granted_scopes");
        assertThat(String.valueOf(authorization.getAdditionalParameters().get("prompt")))
                .doesNotContain("consent");
    }

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
        assertThat(authorization.getAdditionalParameters()).containsEntry("prompt", "select_account");
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
