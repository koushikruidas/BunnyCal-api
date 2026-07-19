package io.bunnycal.availability.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ReservationWindowStatus;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.dto.ReservationWindowResponse;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.service.SessionPinningService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the reservation windows owned by a GROUP event type.
 *
 * Writes bump the host's slot-cache version after commit so that the reservation
 * change is reflected in availability immediately for every event type of the
 * host — a stale window would leave another event type double-bookable, which is
 * exactly what reservation windows exist to prevent.
 */
@Service
public class GroupEventReservationWindowService {

    private final GroupEventReservationWindowRepository windowRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final SessionPinningService sessionPinningService;

    public GroupEventReservationWindowService(
            GroupEventReservationWindowRepository windowRepository,
            EventTypeRepository eventTypeRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            SlotCacheVersionService slotCacheVersionService,
            SessionPinningService sessionPinningService) {
        this.windowRepository = windowRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.slotCacheVersionService = slotCacheVersionService;
        this.sessionPinningService = sessionPinningService;
    }

    @Transactional(readOnly = true)
    public List<ReservationWindowResponse> list(UUID requesterId, UUID eventTypeId) {
        requireOwnedGroupEventType(requesterId, eventTypeId);
        return windowRepository.findByEventTypeIdAndStatus(eventTypeId, ReservationWindowStatus.ACTIVE).stream()
                .map(ReservationWindowResponse::from)
                .toList();
    }

    /**
     * Replaces the full set of reservation windows for the event type.
     *
     * <p>Windows are matched by client-supplied {@code id}, never by content: a
     * non-null id updates that row in place (preserving the identity that sessions
     * link to), a null id inserts, and an existing window absent from the payload is
     * retired. Content-based matching cannot work here — the fields that would form
     * the key are exactly the fields a host edits.
     *
     * <p><b>Legacy compatibility.</b> If no submitted window carries an id, the caller
     * is an old client and the original replace-all behavior is used. This is a
     * whole-payload decision rather than per-window: a mix of ids and nulls is the
     * ordinary new-client case ("my existing windows, plus a new one") and must take
     * the id-keyed path, otherwise adding a window would look like a legacy payload
     * and wipe out lineage. Remove this fallback one release after the frontend ships
     * ids.
     */
    @Transactional
    public List<ReservationWindowResponse> replaceWindows(UUID requesterId,
                                                          UUID eventTypeId,
                                                          List<ReservationWindowRequest> windows) {
        EventType eventType = requireOwnedGroupEventType(requesterId, eventTypeId);

        List<ReservationWindowRequest> safeWindows = windows == null ? List.of() : windows;
        List<ReservationWindowRequest> normalized = normalize(safeWindows);
        validate(normalized);
        validateWithinHostAvailability(eventType.getUserId(), normalized);
        validateNoOverlapWithOtherGroupEvents(eventType.getUserId(), eventTypeId, normalized);

        // An empty payload ("remove all my windows") carries no ids to inspect, but it
        // is a perfectly ordinary new-client action and must still pin booked sessions
        // before their rules retire. Only a *non-empty* payload where every window
        // lacks an id identifies a legacy client, so empty takes the id-keyed path.
        boolean legacyPayload = !normalized.isEmpty()
                && normalized.stream().allMatch(w -> w.id() == null);
        List<ReservationWindowResponse> saved = legacyPayload
                ? replaceAllLegacy(eventTypeId, normalized)
                : upsertByIdentity(eventTypeId, normalized);

        slotCacheVersionService.bumpVersionAfterCommit(eventType.getUserId());
        return saved;
    }

    /**
     * Pre-lineage behavior: delete every window and re-insert. Destroys window ids,
     * so any session lineage pointing at them is severed (the FK nulls it out).
     * Retained only for clients that predate id-carrying payloads.
     */
    private List<ReservationWindowResponse> replaceAllLegacy(UUID eventTypeId,
                                                             List<ReservationWindowRequest> normalized) {
        // Only reached for a non-empty payload with no ids; the empty case is routed to
        // the id-keyed path so it still pins and retires rather than deleting.
        windowRepository.deleteByEventTypeId(eventTypeId);
        return windowRepository.saveAll(
                        normalized.stream().map(w -> toEntity(eventTypeId, w)).toList())
                .stream()
                .map(ReservationWindowResponse::from)
                .toList();
    }

    /**
     * Id-keyed upsert. Existing rows are mutated in place so their identity — and
     * every session that links to it — survives the edit.
     */
    private List<ReservationWindowResponse> upsertByIdentity(UUID eventTypeId,
                                                             List<ReservationWindowRequest> normalized) {
        Map<UUID, GroupEventReservationWindow> existing =
                windowRepository.findByEventTypeIdAndStatus(eventTypeId, ReservationWindowStatus.ACTIVE)
                        .stream()
                        .collect(Collectors.toMap(GroupEventReservationWindow::getId, w -> w));

        Set<UUID> submittedIds = normalized.stream()
                .map(ReservationWindowRequest::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Reject unknown or foreign ids outright rather than silently inserting them:
        // a client sending an id it does not own is a bug, and quietly treating it as
        // an insert would hide that while creating a duplicate window.
        for (UUID id : submittedIds) {
            if (!existing.containsKey(id)) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window " + id + " does not belong to this event type.");
            }
        }

        // Pin before mutating. A window whose schedule changes, or that is going away,
        // may have booked sessions on the old schedule; those keep their time and their
        // guests while future occurrences follow the new rule. Windows edited in ways
        // that do not move any occurrence (a longer end date, say) are left alone so
        // hosts are not shown spurious "these stayed behind" prompts.
        List<UUID> windowsLosingOccurrences = new ArrayList<>();
        for (ReservationWindowRequest w : normalized) {
            if (w.id() != null && scheduleMoved(existing.get(w.id()), w)) {
                windowsLosingOccurrences.add(w.id());
            }
        }
        List<GroupEventReservationWindow> removed = existing.values().stream()
                .filter(w -> !submittedIds.contains(w.getId()))
                .toList();
        removed.forEach(w -> windowsLosingOccurrences.add(w.getId()));
        sessionPinningService.pinBookedSessions(eventTypeId, windowsLosingOccurrences);

        List<GroupEventReservationWindow> toSave = new ArrayList<>();
        for (ReservationWindowRequest w : normalized) {
            toSave.add(w.id() == null
                    ? toEntity(eventTypeId, w)
                    : applyTo(existing.get(w.id()), w));
        }
        List<ReservationWindowResponse> saved = windowRepository.saveAll(toSave).stream()
                .map(ReservationWindowResponse::from)
                .toList();

        retireWindows(removed);

        return saved;
    }

    /**
     * True if the edit moves where occurrences land, as opposed to only changing how
     * long the rule runs.
     *
     * <p>Day-of-week, time-of-day, schedule type and one-time date all relocate
     * occurrences. Recurrence end bounds do not: extending or shortening a series leaves
     * every remaining occurrence exactly where it was, so booked sessions keep tracking
     * the rule. (Sessions past a shortened end bound are handled by the removal path,
     * since their occurrences cease to exist.)
     */
    private boolean scheduleMoved(GroupEventReservationWindow before, ReservationWindowRequest after) {
        if (before == null) {
            return false;
        }
        return before.getScheduleType() != after.scheduleType()
                || before.getDayOfWeek() != after.dayOfWeek()
                || !java.util.Objects.equals(before.getStartTime(), after.startTime())
                || !java.util.Objects.equals(before.getEndTime(), after.endTime())
                || !java.util.Objects.equals(before.getEventDate(), after.eventDate())
                || !java.util.Objects.equals(before.getStartDate(), after.startDate());
    }

    /**
     * Retires windows instead of deleting them, so sessions they generated keep
     * resolvable lineage. Storage is trivial next to the audit and support value.
     */
    private void retireWindows(List<GroupEventReservationWindow> windows) {
        if (windows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (GroupEventReservationWindow w : windows) {
            w.setStatus(ReservationWindowStatus.RETIRED);
            w.setRetiredAt(now);
        }
        windowRepository.saveAll(windows);
    }

    /** Copies submitted values onto an existing row, preserving its id. */
    private GroupEventReservationWindow applyTo(GroupEventReservationWindow target,
                                                 ReservationWindowRequest w) {
        target.setScheduleType(w.scheduleType());
        target.setStartTime(w.startTime());
        target.setEndTime(w.endTime());
        target.setEventDate(w.eventDate());
        target.setDayOfWeek(w.dayOfWeek());
        target.setFrequency(w.frequency());
        target.setStartDate(w.startDate());
        target.setRecurrenceEndMode(w.recurrenceEndMode());
        target.setUntilDate(w.untilDate());
        target.setOccurrenceCount(w.occurrenceCount());
        return target;
    }

    // ── Normalization ──────────────────────────────────────────────────────────

    /**
     * Fills defaults so that callers using the old 3-field form
     * ({@code dayOfWeek}, {@code startTime}, {@code endTime}) continue to work without
     * change. A null {@code scheduleType} is treated as RECURRING; a null
     * {@code recurrenceEndMode} is treated as NONE; a null {@code frequency} defaults
     * to WEEKLY for RECURRING windows.
     */
    private List<ReservationWindowRequest> normalize(List<ReservationWindowRequest> windows) {
        return windows.stream().map(w -> {
            ScheduleType scheduleType = w.scheduleType() != null ? w.scheduleType() : ScheduleType.RECURRING;
            RecurrenceEndMode endMode = w.recurrenceEndMode() != null ? w.recurrenceEndMode() : RecurrenceEndMode.NONE;
            RecurrenceFrequency frequency = (scheduleType == ScheduleType.RECURRING && w.frequency() == null)
                    ? RecurrenceFrequency.WEEKLY : w.frequency();
            return new ReservationWindowRequest(
                    w.id(), // identity must survive normalization
                    scheduleType,
                    w.startTime(),
                    w.endTime(),
                    w.eventDate(),
                    w.dayOfWeek(),
                    frequency,
                    w.startDate(),
                    endMode,
                    w.untilDate(),
                    w.occurrenceCount());
        }).toList();
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    private void validate(List<ReservationWindowRequest> windows) {
        for (ReservationWindowRequest w : windows) {
            if (w.startTime() == null || w.endTime() == null) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window requires startTime and endTime.");
            }
            if (!w.startTime().isBefore(w.endTime())) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window startTime must be before endTime.");
            }
            if (w.scheduleType() == ScheduleType.ONE_TIME) {
                validateOneTime(w);
            } else {
                validateRecurring(w);
            }
        }
        validateNoSelfOverlap(windows);
    }

    private void validateOneTime(ReservationWindowRequest w) {
        if (w.eventDate() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "ONE_TIME reservation window requires eventDate.");
        }
    }

    private void validateRecurring(ReservationWindowRequest w) {
        if (w.dayOfWeek() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "RECURRING reservation window requires dayOfWeek.");
        }
        if (w.startDate() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "RECURRING reservation window requires startDate.");
        }
        RecurrenceEndMode endMode = w.recurrenceEndMode();
        if (endMode == RecurrenceEndMode.UNTIL_DATE) {
            if (w.untilDate() == null) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "UNTIL_DATE recurrence requires untilDate.");
            }
            if (w.untilDate().isBefore(w.startDate())) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "untilDate must be on or after startDate.");
            }
        }
        if (endMode == RecurrenceEndMode.OCCURRENCE_COUNT) {
            if (w.occurrenceCount() == null || w.occurrenceCount() <= 0) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "OCCURRENCE_COUNT recurrence requires occurrenceCount > 0.");
            }
        }
    }

    /**
     * Reject overlapping windows within the same submitted request on the same day
     * (RECURRING) or same date (ONE_TIME).
     */
    private void validateNoSelfOverlap(List<ReservationWindowRequest> windows) {
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                ReservationWindowRequest a = windows.get(i);
                ReservationWindowRequest b = windows.get(j);
                if (sameScheduleKey(a, b) && timesOverlap(a, b)) {
                    String key = a.scheduleType() == ScheduleType.ONE_TIME
                            ? a.eventDate().toString()
                            : a.dayOfWeek().toString();
                    throw new CustomException(ErrorCode.VALIDATION_ERROR,
                            "Reservation windows overlap each other on " + key + ".");
                }
            }
        }
    }

    // ── Host-availability containment ──────────────────────────────────────────

    /**
     * Every reservation window must fall entirely within the host's global availability
     * for the relevant day-of-week. For ONE_TIME windows the relevant day is derived
     * from {@code eventDate}.
     */
    private void validateWithinHostAvailability(UUID hostId, List<ReservationWindowRequest> windows) {
        if (windows.isEmpty()) {
            return;
        }
        List<AvailabilityRule> rules =
                availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(hostId);
        for (ReservationWindowRequest w : windows) {
            DayOfWeek day = w.scheduleType() == ScheduleType.ONE_TIME
                    ? w.eventDate().getDayOfWeek()
                    : w.dayOfWeek();
            boolean contained = rules.stream().anyMatch(rule ->
                    rule.getDayOfWeek() == day
                            && !w.startTime().isBefore(rule.getStartTime())
                            && !w.endTime().isAfter(rule.getEndTime()));
            if (!contained) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window " + day + " " + w.startTime() + "-" + w.endTime()
                                + " is not within the host's availability.");
            }
        }
    }

    // ── Cross-event overlap ────────────────────────────────────────────────────

    /**
     * No reservation window may overlap a window owned by ANOTHER Group Event of the
     * same host. Two group events reserving the same host time would each block the
     * other, leaving that time bookable by neither while claimed by both.
     *
     * Covers both RECURRING (same day-of-week overlap) and ONE_TIME (same event_date
     * overlap) windows.
     */
    private void validateNoOverlapWithOtherGroupEvents(UUID hostId,
                                                       UUID eventTypeId,
                                                       List<ReservationWindowRequest> windows) {
        if (windows.isEmpty()) {
            return;
        }
        List<GroupEventReservationWindow> others =
                windowRepository.findWindowsOwnedByOtherEventTypes(hostId, eventTypeId);
        if (others.isEmpty()) {
            return;
        }
        for (ReservationWindowRequest w : windows) {
            for (GroupEventReservationWindow other : others) {
                if (conflictsWith(w, other)) {
                    String key = w.scheduleType() == ScheduleType.ONE_TIME
                            ? w.eventDate().toString()
                            : w.dayOfWeek().toString();
                    throw new CustomException(ErrorCode.VALIDATION_ERROR,
                            "Reservation window " + key + " " + w.startTime() + "-" + w.endTime()
                                    + " overlaps a reservation owned by another group event.");
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean conflictsWith(ReservationWindowRequest submitted, GroupEventReservationWindow existing) {
        ScheduleType submittedType = submitted.scheduleType() != null ? submitted.scheduleType() : ScheduleType.RECURRING;
        ScheduleType existingType = existing.getScheduleType() != null ? existing.getScheduleType() : ScheduleType.RECURRING;

        if (submittedType != existingType) {
            return false;
        }
        if (submittedType == ScheduleType.ONE_TIME) {
            if (!submitted.eventDate().equals(existing.getEventDate())) {
                return false;
            }
        } else {
            if (submitted.dayOfWeek() != existing.getDayOfWeek()) {
                return false;
            }
        }
        return submitted.startTime().isBefore(existing.getEndTime())
                && existing.getStartTime().isBefore(submitted.endTime());
    }

    private boolean sameScheduleKey(ReservationWindowRequest a, ReservationWindowRequest b) {
        if (a.scheduleType() != b.scheduleType()) {
            return false;
        }
        if (a.scheduleType() == ScheduleType.ONE_TIME) {
            return a.eventDate() != null && a.eventDate().equals(b.eventDate());
        }
        return a.dayOfWeek() != null && a.dayOfWeek() == b.dayOfWeek();
    }

    private boolean timesOverlap(ReservationWindowRequest a, ReservationWindowRequest b) {
        return a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime());
    }

    private GroupEventReservationWindow toEntity(UUID eventTypeId, ReservationWindowRequest w) {
        return GroupEventReservationWindow.builder()
                .eventTypeId(eventTypeId)
                .scheduleType(w.scheduleType())
                .startTime(w.startTime())
                .endTime(w.endTime())
                .eventDate(w.eventDate())
                .dayOfWeek(w.dayOfWeek())
                .frequency(w.frequency())
                .startDate(w.startDate())
                .recurrenceEndMode(w.recurrenceEndMode())
                .untilDate(w.untilDate())
                .occurrenceCount(w.occurrenceCount())
                .build();
    }

    private EventType requireOwnedGroupEventType(UUID requesterId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        if (eventType.getKind() != EventKind.GROUP) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Reservation windows are only supported for GROUP event types.");
        }
        return eventType;
    }
}
