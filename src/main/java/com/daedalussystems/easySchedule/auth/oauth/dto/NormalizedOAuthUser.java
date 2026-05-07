package com.daedalussystems.easySchedule.auth.oauth.dto;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedOAuthUser {

    private AuthProvider provider;
    private String providerUserId;
    private String email;
    private String name;
    private String imageUrl;
}
