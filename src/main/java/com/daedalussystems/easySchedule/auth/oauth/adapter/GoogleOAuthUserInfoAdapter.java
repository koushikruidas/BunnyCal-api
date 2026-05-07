package com.daedalussystems.easySchedule.auth.oauth.adapter;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.auth.oauth.dto.NormalizedOAuthUser;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

@Component
public class GoogleOAuthUserInfoAdapter implements OAuthUserInfoAdapter {

    private static final String GOOGLE_REGISTRATION_ID = "google";

    @Override
    public boolean supports(String registrationId) {
        return GOOGLE_REGISTRATION_ID.equalsIgnoreCase(registrationId);
    }

    @Override
    public NormalizedOAuthUser adapt(Map<String, Object> attributes) {
        String providerUserId = asString(attributes.get("sub"));
        if (providerUserId == null || providerUserId.trim().isEmpty()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("provider_user_id_missing"),
                    "Google OAuth response missing provider user id");
        }

        return NormalizedOAuthUser.builder()
                .provider(AuthProvider.GOOGLE)
                .providerUserId(providerUserId)
                .email(asString(attributes.get("email")))
                .name(asString(attributes.get("name")))
                .imageUrl(asString(attributes.get("picture")))
                .build();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
