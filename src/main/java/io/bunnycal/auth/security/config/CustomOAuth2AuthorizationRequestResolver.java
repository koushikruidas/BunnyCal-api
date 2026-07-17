package io.bunnycal.auth.security.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class CustomOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /**
     * Name of the cookie that remembers which SPA started the OAuth flow, so the success
     * handler can redirect back to the right app. Set only when the request carries
     * {@code ?client=admin}; absent for the customer app (which keeps its existing behavior).
     */
    public static final String OAUTH_CLIENT_COOKIE = "oauthClient";
    public static final String HOST_CALENDAR_COOKIE = "oauthHostCalendar";

    /** Query param the SPA appends to {@code /oauth2/authorization/{registrationId}}. */
    private static final String CLIENT_PARAM = "client";
    private static final String ADMIN_CLIENT = "admin";
    private static final String CALENDAR_PARAM = "calendar";
    private static final String HOST_CALENDAR = "host";

    private static final Set<String> GOOGLE_CALENDAR_SCOPES = Set.of(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.readonly");
    private static final Set<String> MICROSOFT_CALENDAR_SCOPES = Set.of(
            "offline_access",
            "Calendars.ReadWrite",
            "Calendars.Read");

    /** Short TTL: only needs to survive the redirect to the provider and back. */
    private static final int CLIENT_COOKIE_MAX_AGE_SECONDS = 600;

    private final OAuth2AuthorizationRequestResolver defaultResolver;

    public CustomOAuth2AuthorizationRequestResolver(ClientRegistrationRepository repo) {
        this.defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        repo,
                        "/oauth2/authorization"
                );
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest resolved = defaultResolver.resolve(request);
        if (resolved != null) {
            rememberIntent(request);
        }
        return customize(resolved, isHostCalendarIntent(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest resolved = defaultResolver.resolve(request, clientRegistrationId);
        if (resolved != null) {
            rememberIntent(request);
        }
        return customize(resolved, isHostCalendarIntent(request));
    }

    /**
     * When the admin SPA initiates login (with {@code ?client=admin}), persist that intent in a
     * short-lived httpOnly cookie. The provider redirect drops query params, so the cookie is how
     * the post-login success handler knows to send the user back to the admin app. A non-admin
     * (or missing) value writes nothing, so the default customer redirect is preserved.
     */
    private void rememberIntent(HttpServletRequest request) {
        HttpServletResponse response = currentResponse();
        if (response == null) {
            return;
        }
        if (ADMIN_CLIENT.equalsIgnoreCase(request.getParameter(CLIENT_PARAM))) {
            addIntentCookie(response, request, OAUTH_CLIENT_COOKIE, ADMIN_CLIENT);
        }
        if (isHostCalendarIntent(request)) {
            addIntentCookie(response, request, HOST_CALENDAR_COOKIE, HOST_CALENDAR);
        } else {
            clearIntentCookie(response, request, HOST_CALENDAR_COOKIE);
        }
    }

    private static HttpServletResponse currentResponse() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getResponse();
        }
        return null;
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req, boolean hostCalendarIntent) {
        if (req == null) return null;

        Map<String, Object> extraParams = new HashMap<>(req.getAdditionalParameters());
        extraParams.put("prompt", "select_account");

        Set<String> scopes = new HashSet<>(req.getScopes());
        if (hostCalendarIntent) {
            String registrationId = String.valueOf(req.getAttributes().get("registration_id"));
            if ("google".equalsIgnoreCase(registrationId)) {
                scopes.addAll(GOOGLE_CALENDAR_SCOPES);
                extraParams.put("access_type", "offline");
                extraParams.put("include_granted_scopes", "true");
            } else if ("microsoft".equalsIgnoreCase(registrationId)) {
                scopes.addAll(MICROSOFT_CALENDAR_SCOPES);
            }
        }

        return OAuth2AuthorizationRequest.from(req)
                .scopes(scopes)
                .additionalParameters(extraParams)
                .build();
    }

    private static boolean isHostCalendarIntent(HttpServletRequest request) {
        return !ADMIN_CLIENT.equalsIgnoreCase(request.getParameter(CLIENT_PARAM))
                && HOST_CALENDAR.equalsIgnoreCase(request.getParameter(CALENDAR_PARAM));
    }

    private static void addIntentCookie(HttpServletResponse response, HttpServletRequest request,
                                        String name, String value) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(CLIENT_COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    public static void clearIntentCookie(HttpServletResponse response, HttpServletRequest request, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
