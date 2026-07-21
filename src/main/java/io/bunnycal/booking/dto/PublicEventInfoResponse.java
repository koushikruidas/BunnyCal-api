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
        boolean paymentRequired,
        Long paymentAmountMinor,
        String paymentCurrency,
        String paymentProvider
) {
}
