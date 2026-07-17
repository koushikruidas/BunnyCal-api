package io.bunnycal.calendar.domain;

import io.bunnycal.calendar.client.ProviderCalendarInventoryEntry;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What kind of calendar a connected calendar is, so the three behave differently:
 *
 * <ul>
 *   <li>{@link #PRIMARY} — the account's main calendar. Shown on the integrations page, checked for
 *       conflicts by default (its busy events block slots).</li>
 *   <li>{@link #HOLIDAY} — a public-holidays calendar (Google's holiday feed, or a Microsoft
 *       calendar named like one). Never shown, never blocks as busy time, but its all-day entries
 *       mark whole days off. Synced only while the primary is checked.</li>
 *   <li>{@link #OTHER} — everything else: birthdays, subscribed feeds, secondary calendars. Never
 *       shown, never synced, never blocks.</li>
 * </ul>
 *
 * <p>Classification is best-effort and errs safe: anything we cannot confidently place is
 * {@link #OTHER}, never {@link #PRIMARY}. A holiday calendar we fail to recognise merely loses its
 * days-off; it never silently blocks a user's availability. A calendar we wrongly promoted to
 * {@code PRIMARY} would.
 */
public enum CalendarRole {
    PRIMARY,
    HOLIDAY,
    OTHER;

    // Google emits holiday calendars with a stable, language-independent id, e.g.
    // "en.indian#holiday@group.v.calendar.google.com" or
    // "en.usa#holiday@group.v.calendar.google.com". Match the "#holiday@" segment.
    private static final Pattern GOOGLE_HOLIDAY_ID =
            Pattern.compile("#holiday@group\\.v\\.calendar\\.google\\.com$", Pattern.CASE_INSENSITIVE);

    // Microsoft gives no machine signal — only the (localised, renamable) display name. Match the
    // common English forms plus a handful of frequent localisations. This is deliberately a
    // best-effort net: a miss falls through to OTHER (safe), never to PRIMARY.
    private static final Set<String> HOLIDAY_NAME_HINTS = Set.of(
            "holiday", "holidays",
            "public holiday", "public holidays",
            "feiertage",        // de
            "jours fériés",     // fr
            "días festivos", "festivos",  // es
            "feriados",         // pt
            "festività"         // it
    );

    public static CalendarRole classify(CalendarProviderType provider, ProviderCalendarInventoryEntry entry) {
        if (entry == null) {
            return OTHER;
        }
        if (entry.primary()) {
            return PRIMARY;
        }
        if (isHoliday(provider, entry.externalCalendarId(), entry.name())) {
            return HOLIDAY;
        }
        return OTHER;
    }

    public static boolean isHoliday(CalendarProviderType provider, String externalCalendarId, String name) {
        if (provider == CalendarProviderType.GOOGLE) {
            // Google's id is authoritative and language-independent — trust it and nothing else.
            return externalCalendarId != null && GOOGLE_HOLIDAY_ID.matcher(externalCalendarId).find();
        }
        // Microsoft (and anything else): the name is all we have.
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (HOLIDAY_NAME_HINTS.stream().anyMatch(hint -> containsPhrase(normalized, hint))) {
            return true;
        }
        // "India holidays", "UK Holidays 2026" — the word "holiday(s)" as a standalone token.
        return normalized.matches(".*\\bholidays?\\b.*");
    }

    private static boolean containsPhrase(String value, String phrase) {
        return value.equals(phrase)
                || value.startsWith(phrase + " ")
                || value.endsWith(" " + phrase)
                || value.contains(" " + phrase + " ");
    }
}
