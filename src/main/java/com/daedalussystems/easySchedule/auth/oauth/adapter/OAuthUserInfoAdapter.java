package com.daedalussystems.easySchedule.auth.oauth.adapter;

import com.daedalussystems.easySchedule.auth.oauth.dto.NormalizedOAuthUser;
import java.util.Map;

public interface OAuthUserInfoAdapter {

    boolean supports(String registrationId);

    NormalizedOAuthUser adapt(Map<String, Object> attributes);
}
