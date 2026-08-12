package io.bunnycal.booking.dto;

import io.bunnycal.availability.domain.EventKind;
import java.util.List;

public record PublicEventInfoResponse(
        String name,
        long duration,
        String timezone,
        String hostName,
        String hostUsername,
        String description,
        String location,
        String hostAvatarUrl,
        EventKind kind,
        boolean published,
        List<PublicParticipantInfo> participants,
        /**
         * Weekdays the host actually works, as DayOfWeek names ("MONDAY"…"SUNDAY"). The booking
         * calendar used to assume Mon–Fri, so it greyed out a host's Saturday even when they had
         * enabled it, and offered a weekday they had turned off. Only the days are exposed, not
         * the hours.
         */
        List<String> availableDays,
        /**
         * Individual dates ahead that are fully off — the host's own day-offs plus imported public
         * holidays — as ISO dates ("2026-12-25"). A weekday set cannot express these, so without
         * them the calendar left a holiday or vacation day selectable and only revealed it as an
         * empty slot list once the guest clicked. Dates only: an override's label is the host's
         * private note, and this endpoint is unauthenticated.
         */
        List<String> blockedDates,
        boolean paymentRequired,
        Long paymentAmountMinor,
        String paymentCurrency,
        String paymentProvider
) {
}
