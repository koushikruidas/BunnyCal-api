package io.bunnycal.auth.oauth.adapter;

import io.bunnycal.auth.oauth.dto.NormalizedOAuthUser;
import java.util.Map;

public interface OAuthUserInfoAdapter {

    boolean supports(String registrationId);

    NormalizedOAuthUser adapt(Map<String, Object> attributes);
}
