package io.bunnycal.auth.oauth.service;

import io.bunnycal.auth.oauth.adapter.OAuthUserInfoAdapter;
import io.bunnycal.auth.oauth.dto.NormalizedOAuthUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    // We keep a normalized key so downstream logic can be provider-agnostic.
    private static final String NAME_ATTRIBUTE_KEY = "providerUserId";

    private final List<OAuthUserInfoAdapter> adapters;
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = delegate.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuthUserInfoAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(registrationId))
                .findFirst()
                .orElseThrow(() -> new OAuth2AuthenticationException(
                        new OAuth2Error("unsupported_provider"),
                        "No adapter found for provider: " + registrationId));

        NormalizedOAuthUser normalized = adapter.adapt(oauth2User.getAttributes());
        if (normalized.getEmail() == null || normalized.getEmail().trim().isEmpty()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_missing"),
                    "OAuth provider did not return email");
        }

        // Intentionally augment provider attributes with normalized fields for downstream consumers.
        Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());
        attributes.put("provider", normalized.getProvider().name());
        attributes.put("providerUserId", normalized.getProviderUserId());
        attributes.put("email", normalized.getEmail());
        attributes.put("name", normalized.getName());
        attributes.put("imageUrl", normalized.getImageUrl());

        return new DefaultOAuth2User(oauth2User.getAuthorities(), attributes, NAME_ATTRIBUTE_KEY);
    }
}
