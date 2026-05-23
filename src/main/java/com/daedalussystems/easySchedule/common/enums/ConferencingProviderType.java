package com.daedalussystems.easySchedule.common.enums;

import java.util.Locale;
import java.util.Optional;

public enum ConferencingProviderType {
    NONE,
    GOOGLE_MEET,
    MICROSOFT_TEAMS,
    ZOOM,
    CUSTOM_URL;

    /**
     * Canonicalise an externally supplied provider identifier (path variable,
     * query parameter, request body field) into one of the enum constants.
     *
     * <p>Tolerates case differences, hyphen/underscore variants and surrounding
     * whitespace so that {@code google_meet}, {@code GOOGLE-MEET},
     * {@code  Google Meet } all resolve to {@link #GOOGLE_MEET}.
     */
    public static Optional<ConferencingProviderType> fromExternal(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String canonical = raw.trim();
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        canonical = canonical
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (canonical.contains("__")) {
            canonical = canonical.replace("__", "_");
        }
        try {
            return Optional.of(ConferencingProviderType.valueOf(canonical));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Lower-case, underscore-separated identifier suitable for URL paths and JSON payloads. */
    public String externalId() {
        return name().toLowerCase(Locale.ROOT);
    }
}
