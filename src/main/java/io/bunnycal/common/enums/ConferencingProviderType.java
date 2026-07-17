package io.bunnycal.common.enums;

import java.util.Locale;
import java.util.Optional;

public enum ConferencingProviderType {
    /**
     * "Use my default meeting link" — a pointer, not a value. Stored on an event type that has not
     * been pinned to a specific provider; resolved against the writer's current global default at
     * booking time by {@code EventConferencingResolver}.
     *
     * <p>This is what keeps a booking from silently losing its join link when the user later changes
     * their write-back calendar: Google Meet can only be minted on a Google calendar and Teams only
     * on a Microsoft one, so an event type that <em>froze</em> {@code GOOGLE_MEET} would break the
     * day its owner switched to Outlook. A {@code DEFAULT} event type simply follows.
     *
     * <p>Consequently {@link #GOOGLE_MEET} and {@link #MICROSOFT_TEAMS} are reachable <em>only</em>
     * through this pointer — they are never independently selectable. The provider-independent
     * choices ({@link #ZOOM}, {@link #CUSTOM_URL}, {@link #NONE}) may be pinned freely, because no
     * calendar provider is required to produce them.
     *
     * <p><b>Never let this constant reach a {@code == GOOGLE_MEET} comparison.</b> Resolve it first.
     */
    DEFAULT,
    NONE,
    GOOGLE_MEET,
    MICROSOFT_TEAMS,
    ZOOM,
    CUSTOM_URL;

    /** The provider-coupled types: they can only be minted on a matching calendar. */
    public boolean requiresCalendarProvider() {
        return this == GOOGLE_MEET || this == MICROSOFT_TEAMS;
    }

    /** True for {@link #DEFAULT} — a pointer that must be resolved before any behavioural branch. */
    public boolean isPointer() {
        return this == DEFAULT;
    }

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
