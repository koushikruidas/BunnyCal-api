package com.daedalussystems.easySchedule.oauth.adapter;

import com.daedalussystems.easySchedule.oauth.dto.NormalizedOAuthUser;
import java.util.Map;

public interface OAuthUserInfoAdapter {

    boolean supports(String registrationId);

    NormalizedOAuthUser adapt(Map<String, Object> attributes);
}
