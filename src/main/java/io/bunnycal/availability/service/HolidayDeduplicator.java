package io.bunnycal.availability.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collapses the near-duplicate holiday entries that connecting several calendars produces, so a
 * single occasion becomes a single day off.
 *
 * <p>Two problems, two passes — ported verbatim from the dashboard's holiday card
 * ({@code DashboardPage.tsx}) so the bookable calendar and the displayed list agree exactly:
 *
 * <ol>
 *   <li><b>Same occasion, slightly different name or a day or two apart.</b> Two regional calendars
 *       report "Rath Yatra" on the 16th and the 17th. Group by normalised title; within a title,
 *       collapse anything within a 3-day window of an already-kept date, keeping the earliest date
 *       and the more descriptive (shorter, non-generic) title. So the pair becomes one day off, not
 *       two.</li>
 *   <li><b>Same date, generic vs named.</b> One calendar says "Holiday", another says "Independence
 *       Day", both on the 15th. Drop the generic one when a descriptive title shares its date.</li>
 * </ol>
 *
 * <p>Deliberately faithful to the source, including its limit: a slash-combined label
 * ("Rath Yatra / Raksha Bandhan") is treated as one distinct title and is not split — it will not
 * collapse against "Rath Yatra" alone.
 */
public final class HolidayDeduplicator {

    private static final int DEDUPE_WINDOW_DAYS = 3;

    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Set<String> GENERIC_TITLES = Set.of(
            "holiday",
            "public holiday",
            "bank holiday",
            "national holiday",
            "regional holiday",
            "observance");

    private HolidayDeduplicator() {}

    public record Holiday(String title, LocalDate date) {}

    /** The distinct dates that should be treated as days off, after both dedupe passes. */
    public static Set<LocalDate> offDates(List<Holiday> holidays) {
        Set<LocalDate> dates = new java.util.TreeSet<>();
        for (Holiday h : dedupe(holidays)) {
            dates.add(h.date());
        }
        return dates;
    }

    public static List<Holiday> dedupe(List<Holiday> holidays) {
        if (holidays == null || holidays.isEmpty()) {
            return List.of();
        }
        List<Holiday> sorted = new ArrayList<>(holidays);
        sorted.removeIf(h -> h == null || h.date() == null);
        sorted.sort((a, b) -> a.date().compareTo(b.date()));

        // Pass 1 — same normalised title within a 3-day window collapses to the earliest date.
        Map<String, List<Holiday>> keptByTitle = new LinkedHashMap<>();
        List<Holiday> titleDeduped = new ArrayList<>();
        for (Holiday candidate : sorted) {
            String key = normalizeTitle(candidate.title());
            List<Holiday> kept = keptByTitle.computeIfAbsent(key, k -> new ArrayList<>());
            Holiday duplicateOf = null;
            for (Holiday k : kept) {
                if (Math.abs(daysBetween(k.date(), candidate.date())) <= DEDUPE_WINDOW_DAYS) {
                    duplicateOf = k;
                    break;
                }
            }
            if (duplicateOf == null) {
                kept.add(candidate);
                titleDeduped.add(candidate);
            } else if (title(candidate).length() < title(duplicateOf).length()) {
                // Prefer the shorter (usually more canonical) label, keeping the earlier date.
                int idx = titleDeduped.indexOf(duplicateOf);
                if (idx >= 0) {
                    titleDeduped.set(idx, new Holiday(candidate.title(), duplicateOf.date()));
                }
            }
        }

        // Pass 2 — a generic-titled entry sharing a date with a descriptive one is dropped.
        Map<LocalDate, List<Holiday>> byDate = new LinkedHashMap<>();
        for (Holiday h : titleDeduped) {
            byDate.computeIfAbsent(h.date(), d -> new ArrayList<>()).add(h);
        }
        List<Holiday> unique = new ArrayList<>();
        for (Holiday h : titleDeduped) {
            if (!isGeneric(h.title())) {
                unique.add(h);
                continue;
            }
            List<Holiday> sameDate = byDate.getOrDefault(h.date(), List.of());
            boolean hasDescriptivePeer = sameDate.stream().anyMatch(o -> o != h && !isGeneric(o.title()));
            if (!hasDescriptivePeer) {
                unique.add(h);
            }
        }
        unique.sort((a, b) -> a.date().compareTo(b.date()));
        return unique;
    }

    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String stripped = TRAILING_PARENTHETICAL.matcher(title).replaceAll("");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    static boolean isGeneric(String title) {
        return GENERIC_TITLES.contains(normalizeTitle(title));
    }

    private static String title(Holiday h) {
        return h.title() == null ? "" : h.title();
    }

    private static long daysBetween(LocalDate a, LocalDate b) {
        return a.toEpochDay() - b.toEpochDay();
    }
}
