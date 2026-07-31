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

    /**
     * Remembers the origin the login was started from, so the success handler can send the user
     * back to the site they actually came from.
     *
     * Without this the redirect always went to the single configured {@code FRONTEND_BASE_URL},
     * which meant signing in on stage.bunnycal.io dropped you on bunnycal.io/dashboard — a
     * different host, where the staging session does not exist.
     *
     * The value is only ever honoured if it matches the configured CORS allowlist (see
     * OAuth2AuthenticationSuccessHandler), so this cannot be used to redirect somewhere arbitrary.
     */
    public static final String OAUTH_ORIGIN_COOKIE = "oauthOrigin";

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
        rememberOrigin(request, response);
    }

    /**
     * Record which site started the flow. The provider redirect lands on the API with no Origin
     * or Referer from the original SPA, so it has to be captured here, at the point the browser
     * still has that context.
     *
     * Referer is the fallback because a top-level navigation to
     * {@code /oauth2/authorization/{id}} is not a CORS request and carries no Origin header.
     */
    private void rememberOrigin(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (!hasText(origin)) {
            origin = originOf(request.getHeader("Referer"));
        }
        if (hasText(origin)) {
            addIntentCookie(response, request, OAUTH_ORIGIN_COOKIE, origin);
        } else {
            clearIntentCookie(response, request, OAUTH_ORIGIN_COOKIE);
        }
    }

    /** Reduce a full URL to scheme://host[:port], or null if it cannot be parsed. */
    private static String originOf(String url) {
        if (!hasText(url)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getPort() == -1
                    ? uri.getScheme() + "://" + uri.getHost()
                    : uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
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
