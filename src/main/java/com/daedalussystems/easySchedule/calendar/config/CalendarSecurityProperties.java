package com.daedalussystems.easySchedule.calendar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "calendar.security")
public class CalendarSecurityProperties {
    private String encryptionKeyBase64;
    private String oauthStateSecret;

    public String getEncryptionKeyBase64() {
        return encryptionKeyBase64;
    }

    public void setEncryptionKeyBase64(String encryptionKeyBase64) {
        this.encryptionKeyBase64 = encryptionKeyBase64;
    }

    public String getOauthStateSecret() {
        return oauthStateSecret;
    }

    public void setOauthStateSecret(String oauthStateSecret) {
        this.oauthStateSecret = oauthStateSecret;
    }
}
