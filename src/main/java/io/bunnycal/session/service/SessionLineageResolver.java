package io.bunnycal.session.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.engine.RecurrenceWindowFilter;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.common.time.TimeConversionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves which reservation window generated a session at a given instant.
 *
 * <p>Lineage is derived here rather than threaded through the public booking path
 * because the identifying inputs — event type, host, and start instant — are already
 * available where a session is materialized. Passing a window id down through
 * {@code PublicBookingService} would widen several signatures to carry something
 * this can compute directly.
 *
 * <p>Resolution is best-effort by design. Empty is a legitimate outcome — ad-hoc
 * sessions, or a slot whose window was retired between listing and booking — and
 * must never block a booking: lineage is metadata for later host operations, not a
 * booking precondition.
 */
@Component
public class SessionLineageResolver {

    private final GroupEventReservationWindowRepository windowRepository;
    private final UserRepository userRepository;
    private final TimeConversionService timeConversionService;

    public SessionLineageResolver(GroupEventReservationWindowRepository windowRepository,
                                  UserRepository userRepository,
                                  TimeConversionService timeConversionService) {
        this.windowRepository = windowRepository;
        this.userRepository = userRepository;
        this.timeConversionService = timeConversionService;
    }

    /**
     * Returns the ACTIVE window whose recurrence covers {@code startTime} and whose
     * time-of-day contains it, or empty if none does.
     *
     * <p>Comparison happens in the host's timezone: reservation windows are stored as
     * wall-clock times against a day-of-week, so a UTC instant only maps back to a
     * window through the zone the host defined it in — the same convention
     * {@code SlotService} uses when generating these slots.
     */
    public Optional<GroupEventReservationWindow> resolveWindow(UUID hostId,
                                                                UUID eventTypeId,
                                                                Instant startTime) {
        ZoneId zone = userRepository.findById(hostId)
                .map(User::getTimezone)
                .map(timeConversionService::resolveZone)
                .orElse(null);
        if (zone == null) {
            return Optional.empty();
        }

        ZonedDateTime local = startTime.atZone(zone);
        LocalDate date = local.toLocalDate();
        LocalTime timeOfDay = local.toLocalTime();

        List<GroupEventReservationWindow> candidates = windowRepository.findCandidateWindowsForDate(
                eventTypeId, date, local.getDayOfWeek().name());

        return candidates.stream()
                // The SQL pre-filter matches schedule type and weekday only; end-bound
                // logic (UNTIL_DATE, OCCURRENCE_COUNT) always belongs to this filter.
                .filter(w -> RecurrenceWindowFilter.appliesOn(w, date))
                .filter(w -> !timeOfDay.isBefore(w.getStartTime()) && timeOfDay.isBefore(w.getEndTime()))
                .findFirst();
    }
}
