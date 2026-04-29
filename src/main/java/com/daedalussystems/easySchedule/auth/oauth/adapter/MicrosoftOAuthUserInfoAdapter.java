package com.daedalussystems.easySchedule.auth.oauth.adapter;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.auth.oauth.dto.NormalizedOAuthUser;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

@Component
public class MicrosoftOAuthUserInfoAdapter implements OAuthUserInfoAdapter {

    private static final String MICROSOFT_REGISTRATION_ID = "microsoft";

    @Override
    public boolean supports(String registrationId) {
        return MICROSOFT_REGISTRATION_ID.equalsIgnoreCase(registrationId);
    }

    @Override
    public NormalizedOAuthUser adapt(Map<String, Object> attributes) {
        String providerUserId = asString(attributes.get("id"));
        if (providerUserId == null || providerUserId.trim().isEmpty()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("provider_user_id_missing"),
                    "Microsoft OAuth response missing provider user id");
        }

        String email = asString(attributes.get("email"));
        if (email == null || email.trim().isEmpty()) {
            email = asString(attributes.get("userPrincipalName"));
        }

        return NormalizedOAuthUser.builder()
                .provider(AuthProvider.MICROSOFT)
                .providerUserId(providerUserId)
                .email(email)
                .name(asString(attributes.get("name")))
                .build();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
