package io.bunnycal.availability.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.dto.ReservationWindowResponse;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the recurring reservation windows owned by a GROUP event type.
 *
 * Writes bump the host's slot-cache version after commit so that the reservation
 * change is reflected in availability immediately for every event type of the
 * host -- a stale window would leave another event type double-bookable, which is
 * exactly what reservation windows exist to prevent.
 */
@Service
public class GroupEventReservationWindowService {

    private final GroupEventReservationWindowRepository windowRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final SlotCacheVersionService slotCacheVersionService;

    public GroupEventReservationWindowService(
            GroupEventReservationWindowRepository windowRepository,
            EventTypeRepository eventTypeRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            SlotCacheVersionService slotCacheVersionService) {
        this.windowRepository = windowRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.slotCacheVersionService = slotCacheVersionService;
    }

    @Transactional(readOnly = true)
    public List<ReservationWindowResponse> list(UUID requesterId, UUID eventTypeId) {
        requireOwnedGroupEventType(requesterId, eventTypeId);
        return windowRepository.findByEventTypeId(eventTypeId).stream()
                .map(ReservationWindowResponse::from)
                .toList();
    }

    /**
     * Replaces the full set of reservation windows for the event type (bulk upsert),
     * mirroring {@link AvailabilityService#replaceRules} semantics.
     */
    @Transactional
    public List<ReservationWindowResponse> replaceWindows(UUID requesterId,
                                                          UUID eventTypeId,
                                                          List<ReservationWindowRequest> windows) {
        EventType eventType = requireOwnedGroupEventType(requesterId, eventTypeId);

        List<ReservationWindowRequest> safeWindows = windows == null ? List.of() : windows;
        validate(safeWindows);
        validateWithinHostAvailability(eventType.getUserId(), safeWindows);
        validateNoOverlapWithOtherGroupEvents(eventType.getUserId(), eventTypeId, safeWindows);

        windowRepository.deleteByEventTypeId(eventTypeId);

        List<GroupEventReservationWindow> toSave = safeWindows.stream()
                .map(w -> GroupEventReservationWindow.builder()
                        .eventTypeId(eventTypeId)
                        .dayOfWeek(w.dayOfWeek())
                        .startTime(w.startTime())
                        .endTime(w.endTime())
                        .build())
                .toList();

        List<ReservationWindowResponse> saved = windowRepository.saveAll(toSave).stream()
                .map(ReservationWindowResponse::from)
                .toList();

        slotCacheVersionService.bumpVersionAfterCommit(eventType.getUserId());
        return saved;
    }

    private void validate(List<ReservationWindowRequest> windows) {
        for (ReservationWindowRequest w : windows) {
            if (w.dayOfWeek() == null || w.startTime() == null || w.endTime() == null) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window requires dayOfWeek, startTime, and endTime.");
            }
            if (!w.startTime().isBefore(w.endTime())) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window startTime must be before endTime.");
            }
        }
        // Reject overlaps WITHIN the submitted set (same day-of-week) -- otherwise a
        // single request could define self-conflicting windows.
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                ReservationWindowRequest a = windows.get(i);
                ReservationWindowRequest b = windows.get(j);
                if (a.dayOfWeek() == b.dayOfWeek()
                        && a.startTime().isBefore(b.endTime())
                        && b.startTime().isBefore(a.endTime())) {
                    throw new CustomException(ErrorCode.VALIDATION_ERROR,
                            "Reservation windows overlap each other on " + a.dayOfWeek() + ".");
                }
            }
        }
    }

    /**
     * Every reservation window must fall entirely within the host's global
     * availability for that day-of-week. A host cannot reserve time they are not
     * even open for -- that would block other event types during hours the host
     * never offers, and the owning group event would show slots outside its own
     * working hours.
     */
    private void validateWithinHostAvailability(UUID hostId, List<ReservationWindowRequest> windows) {
        if (windows.isEmpty()) {
            return;
        }
        List<AvailabilityRule> rules =
                availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(hostId);
        for (ReservationWindowRequest w : windows) {
            boolean contained = rules.stream().anyMatch(rule ->
                    rule.getDayOfWeek() == w.dayOfWeek()
                            && !w.startTime().isBefore(rule.getStartTime())
                            && !w.endTime().isAfter(rule.getEndTime()));
            if (!contained) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Reservation window " + w.dayOfWeek() + " " + w.startTime() + "-" + w.endTime()
                                + " is not within the host's availability.");
            }
        }
    }

    /**
     * No reservation window may overlap a window owned by ANOTHER Group Event of the
     * same host. Two group events reserving the same host time would each block the
     * other, leaving that time bookable by neither while claimed by both.
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
                DayOfWeek otherDay = other.getDayOfWeek();
                if (otherDay == w.dayOfWeek()
                        && w.startTime().isBefore(other.getEndTime())
                        && other.getStartTime().isBefore(w.endTime())) {
                    throw new CustomException(ErrorCode.VALIDATION_ERROR,
                            "Reservation window " + w.dayOfWeek() + " " + w.startTime() + "-" + w.endTime()
                                    + " overlaps a reservation owned by another group event.");
                }
            }
        }
    }

    private EventType requireOwnedGroupEventType(UUID requesterId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findByIdAndUserId(eventTypeId, requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        if (eventType.getKind() != EventKind.GROUP) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Reservation windows are only supported for GROUP event types.");
        }
        return eventType;
    }
}
