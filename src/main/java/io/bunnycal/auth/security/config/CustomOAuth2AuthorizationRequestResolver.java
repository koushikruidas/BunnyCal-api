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
import java.util.Map;

@Component
public class CustomOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /**
     * Name of the cookie that remembers which SPA started the OAuth flow, so the success
     * handler can redirect back to the right app. Set only when the request carries
     * {@code ?client=admin}; absent for the customer app (which keeps its existing behavior).
     */
    public static final String OAUTH_CLIENT_COOKIE = "oauthClient";

    /** Query param the SPA appends to {@code /oauth2/authorization/{registrationId}}. */
    private static final String CLIENT_PARAM = "client";
    private static final String ADMIN_CLIENT = "admin";

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
            rememberClient(request);
        }
        return customize(resolved);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest resolved = defaultResolver.resolve(request, clientRegistrationId);
        if (resolved != null) {
            rememberClient(request);
        }
        return customize(resolved);
    }

    /**
     * When the admin SPA initiates login (with {@code ?client=admin}), persist that intent in a
     * short-lived httpOnly cookie. The provider redirect drops query params, so the cookie is how
     * the post-login success handler knows to send the user back to the admin app. A non-admin
     * (or missing) value writes nothing, so the default customer redirect is preserved.
     */
    private void rememberClient(HttpServletRequest request) {
        if (!ADMIN_CLIENT.equalsIgnoreCase(request.getParameter(CLIENT_PARAM))) {
            return;
        }
        HttpServletResponse response = currentResponse();
        if (response == null) {
            return;
        }
        Cookie cookie = new Cookie(OAUTH_CLIENT_COOKIE, ADMIN_CLIENT);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(CLIENT_COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    private static HttpServletResponse currentResponse() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getResponse();
        }
        return null;
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req) {
        if (req == null) return null;

        Map<String, Object> extraParams = new HashMap<>(req.getAdditionalParameters());
        extraParams.put("prompt", "select_account");

        return OAuth2AuthorizationRequest.from(req)
                .additionalParameters(extraParams)
                .build();
    }
}
