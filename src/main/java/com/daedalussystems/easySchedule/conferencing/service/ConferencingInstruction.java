package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;

public record ConferencingInstruction(
        ConferencingProviderType providerType,
        Mode mode,
        String joinUrl,
        String hostUrl,
        String meetingId) {

    public enum Mode {
        NONE,
        URL_EMBEDDED,
        REQUEST_NATIVE_MEET
    }

    public static ConferencingInstruction none() {
        return new ConferencingInstruction(ConferencingProviderType.NONE, Mode.NONE, null, null, null);
    }

    public static ConferencingInstruction urlEmbedded(ConferencingProviderType providerType,
                                                      String joinUrl,
                                                      String hostUrl,
                                                      String meetingId) {
        return new ConferencingInstruction(providerType, Mode.URL_EMBEDDED, joinUrl, hostUrl, meetingId);
    }

    public static ConferencingInstruction requestNativeMeet() {
        return new ConferencingInstruction(ConferencingProviderType.GOOGLE_MEET, Mode.REQUEST_NATIVE_MEET, null, null, null);
    }

    public boolean requestsNativeMeet() {
        return mode == Mode.REQUEST_NATIVE_MEET;
    }

    public boolean embedsExternalUrl() {
        return mode == Mode.URL_EMBEDDED && joinUrl != null && !joinUrl.isBlank();
    }
}
