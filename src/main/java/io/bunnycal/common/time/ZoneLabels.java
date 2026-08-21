package io.bunnycal.common.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Names a timezone the way a reader recognises it — "IST", "EDT", "JST" — rather than by its IANA
 * id.
 *
 * <p>Notification mails used to print {@code ZoneId#getId}, so a confirmation read
 * "13:30–14:00 (Asia/Kolkata)". That is precise and unambiguous, and nobody says it out loud; the
 * rest of the product (and every calendar client the invite lands in) says IST.
 *
 * <p>The abbreviation is resolved <b>at the meeting's instant</b>, not for the zone in the
 * abstract, because it is seasonal: {@code America/New_York} is EST in January and EDT in July, and
 * a booking must be labelled with whichever was in force when it happens. Java's CLDR data covers
 * every zone we have needed so far; where it has no short name it returns a GMT offset, which is
 * still a true statement about the time and is left alone.
 */
public final class ZoneLabels {
    private static final DateTimeFormatter ABBREVIATION =
            DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

    private ZoneLabels() {
    }

    /**
     * Resolve a zone id, falling back to the supplied default when it is missing or unparseable.
     *
     * @param fallback used when {@code timezone} is blank or not a zone; never null in practice —
     *        callers pass the host's zone, which is the meeting's anchor.
     */
    public static ZoneId zoneOrDefault(String timezone, ZoneId fallback) {
        ZoneId safeFallback = fallback == null ? ZoneOffset.UTC : fallback;
        if (timezone == null || timezone.isBlank()) {
            return safeFallback;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException ex) {
            return safeFallback;
        }
    }

    /** "IST", "EDT", "AEST" — the abbreviation in force at {@code instant}. */
    public static String abbreviation(Instant instant, ZoneId zone) {
        if (instant == null || zone == null) {
            return "";
        }
        return ABBREVIATION.format(ZonedDateTime.ofInstant(instant, zone));
    }

    /** As {@link #abbreviation(Instant, ZoneId)}, resolving the zone id first. */
    public static String abbreviation(Instant instant, String timezone, ZoneId fallback) {
        return abbreviation(instant, zoneOrDefault(timezone, fallback));
    }
}
