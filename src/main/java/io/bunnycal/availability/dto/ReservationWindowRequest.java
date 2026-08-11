package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Request to create or replace a GROUP event type's reservation window.
 *
 * <p><b>Identity is declared by the client, never inferred by the server.</b> A
 * non-null {@code id} means "update this existing window in place"; a null
 * {@code id} means "insert a new window"; an existing window absent from the
 * submitted list is retired. Matching windows by their content instead
 * ({@code dayOfWeek} + {@code startTime} + …) cannot work, because every field in
 * such a key is exactly what the host edits — and splitting one window into two or
 * merging two into one has no correct content-based answer.
 *
 * <p>Backward-compatible: callers that only send {@code dayOfWeek}, {@code startTime},
 * and {@code endTime} (the original 3-field form) will have {@code scheduleType}
 * defaulted to {@link ScheduleType#RECURRING} and {@code recurrenceEndMode} defaulted
 * to {@link RecurrenceEndMode#NONE} by the service. A payload in which <em>no</em>
 * window carries an {@code id} is treated as a legacy client and handled with the
 * original replace-all semantics; see
 * {@code GroupEventReservationWindowService.replaceWindows}.
 *
 * <p>Field rules (enforced by the service, not this record):
 * <ul>
 *   <li>ONE_TIME: {@code eventDate} required; {@code dayOfWeek} ignored.</li>
 *   <li>RECURRING: {@code dayOfWeek} required; {@code frequency} defaults to WEEKLY.
 *       {@code startDate} is optional and means "no lower bound" when absent, so the
 *       window simply runs from today — which is what the create-event form offers.</li>
 *   <li>UNTIL_DATE: {@code untilDate} required, and {@code >= startDate} when one is given.</li>
 *   <li>OCCURRENCE_COUNT: {@code occurrenceCount > 0} and {@code startDate} both required —
 *       occurrences are counted in whole weeks from {@code startDate}, so the count is
 *       meaningless without it.</li>
 * </ul>
 */
public record ReservationWindowRequest(
        /** Existing window to update; null to insert a new one. */
        UUID id,
        ScheduleType scheduleType,
        LocalTime startTime,
        LocalTime endTime,
        // ONE_TIME fields
        LocalDate eventDate,
        // RECURRING fields
        DayOfWeek dayOfWeek,
        RecurrenceFrequency frequency,
        LocalDate startDate,
        RecurrenceEndMode recurrenceEndMode,
        LocalDate untilDate,
        Integer occurrenceCount) {}
