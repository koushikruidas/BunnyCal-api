package io.bunnycal.availability.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventAvailabilityMode;
import io.bunnycal.availability.domain.EventAvailabilityWindow;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.EventAvailabilityScheduleRequest;
import io.bunnycal.availability.dto.EventAvailabilityScheduleResponse;
import io.bunnycal.availability.dto.EventAvailabilityWindowRequest;
import io.bunnycal.availability.dto.EventAvailabilityWindowResponse;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventAvailabilityWindowRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the recurring custom schedule of a demand-driven event type
 * (ONE_ON_ONE, ROUND_ROBIN, COLLECTIVE).
 *
 * Crucially, this service NEVER writes to {@code availability_rules}: host-global
 * working hours are owned exclusively by {@link AvailabilityService}. A ONE_ON_ONE
 * custom schedule replaces those global weekly hours for that event. For multi-host
 * kinds it defines the event's operating window but never overrides a participant's
 * own availability. These windows reserve no time and block no other event type.
 *
 * Writes bump the host's slot-cache version after commit so the change is reflected
 * in availability immediately (the 60s TTL alone would otherwise leave a stale view
 * of the event's bookable window).
 */
@Service
public class EventAvailabilityWindowService {

    private final EventAvailabilityWindowRepository windowRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final SlotCacheVersionService slotCacheVersionService;

    public EventAvailabilityWindowService(
            EventAvailabilityWindowRepository windowRepository,
            EventTypeRepository eventTypeRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            SlotCacheVersionService slotCacheVersionService) {
        this.windowRepository = windowRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.slotCacheVersionService = slotCacheVersionService;
    }

    @Transactional(readOnly = true)
    public List<EventAvailabilityWindowResponse> list(UUID requesterId, UUID eventTypeId) {
        requireOwnedDemandDrivenEventType(requesterId, eventTypeId);
        return listWindows(eventTypeId);
    }

    @Transactional(readOnly = true)
    public EventAvailabilityScheduleResponse getSchedule(UUID requesterId, UUID eventTypeId) {
        EventType eventType = requireOwnedDemandDrivenEventType(requesterId, eventTypeId);
        return new EventAvailabilityScheduleResponse(effectiveMode(eventType), listWindows(eventTypeId));
    }

    /**
     * Backward-compatible replacement through the original filter API. These callers
     * retain the historical narrow-only validation; new clients should use
     * {@link #replaceSchedule(UUID, UUID, EventAvailabilityScheduleRequest)}.
     * Unlike GROUP reservation windows there is NO cross-event overlap check and NO
     * self-overlap rejection -- a custom schedule owns nothing, so overlapping windows
     * across event types (or even a redundant overlap within one type) are harmless.
     */
    @Transactional
    public List<EventAvailabilityWindowResponse> replaceWindows(UUID requesterId,
                                                                UUID eventTypeId,
                                                                List<EventAvailabilityWindowRequest> windows) {
        EventType eventType = requireOwnedDemandDrivenEventType(requesterId, eventTypeId);

        List<EventAvailabilityWindowRequest> safeWindows = windows == null ? List.of() : windows;
        validate(safeWindows);
        // Backward-compatible behavior for callers of the original FILTER endpoint.
        // The new schedule endpoint below is the explicit path that permits expansion.
        validateWithinHostAvailability(eventType.getUserId(), safeWindows);

        EventAvailabilityMode mode = safeWindows.isEmpty()
                ? EventAvailabilityMode.INHERIT
                : EventAvailabilityMode.CUSTOM;
        return persistSchedule(eventType, mode, safeWindows).windows();
    }

    /**
     * Atomically replaces both schedule mode and windows. CUSTOM windows are deliberately
     * independent of the owner's global weekly hours, so they may be narrower or wider.
     * Participant-owned availability remains authoritative in multi-host slot computation.
     */
    @Transactional
    public EventAvailabilityScheduleResponse replaceSchedule(UUID requesterId,
                                                              UUID eventTypeId,
                                                              EventAvailabilityScheduleRequest request) {
        EventType eventType = requireOwnedDemandDrivenEventType(requesterId, eventTypeId);
        if (request == null || request.mode() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Availability schedule mode is required.");
        }
        List<EventAvailabilityWindowRequest> safeWindows = request.windows() == null
                ? List.of()
                : request.windows();
        if (request.mode() == EventAvailabilityMode.INHERIT && !safeWindows.isEmpty()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Inherited availability must not include custom windows.");
        }
        validate(safeWindows);
        return persistSchedule(eventType, request.mode(), safeWindows);
    }

    private EventAvailabilityScheduleResponse persistSchedule(EventType eventType,
                                                               EventAvailabilityMode mode,
                                                               List<EventAvailabilityWindowRequest> windows) {

        windowRepository.deleteByEventTypeId(eventType.getId());

        List<EventAvailabilityWindow> toSave = windows.stream()
                .map(w -> EventAvailabilityWindow.builder()
                        .eventTypeId(eventType.getId())
                        .dayOfWeek(w.dayOfWeek())
                        .startTime(w.startTime())
                        .endTime(w.endTime())
                        .build())
                .toList();

        List<EventAvailabilityWindowResponse> saved = windowRepository.saveAll(toSave).stream()
                .map(EventAvailabilityWindowResponse::from)
                .toList();

        eventType.setAvailabilityMode(mode);
        eventTypeRepository.save(eventType);
        slotCacheVersionService.bumpVersionAfterCommit(eventType.getUserId());
        return new EventAvailabilityScheduleResponse(mode, saved);
    }

    private List<EventAvailabilityWindowResponse> listWindows(UUID eventTypeId) {
        return windowRepository.findByEventTypeId(eventTypeId).stream()
                .map(EventAvailabilityWindowResponse::from)
                .toList();
    }

    private static EventAvailabilityMode effectiveMode(EventType eventType) {
        return eventType.getAvailabilityMode() == null
                ? EventAvailabilityMode.INHERIT
                : eventType.getAvailabilityMode();
    }

    private void validate(List<EventAvailabilityWindowRequest> windows) {
        for (EventAvailabilityWindowRequest w : windows) {
            if (w.dayOfWeek() == null || w.startTime() == null || w.endTime() == null) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Availability window requires dayOfWeek, startTime, and endTime.");
            }
            if (!w.startTime().isBefore(w.endTime())) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Availability window startTime must be before endTime.");
            }
        }
    }

    /**
     * Historical validation retained only for callers of the legacy windows endpoint.
     * The schedule endpoint deliberately does not call this method because CUSTOM may
     * extend beyond the owner's global weekly hours.
     */
    private void validateWithinHostAvailability(UUID hostId, List<EventAvailabilityWindowRequest> windows) {
        if (windows.isEmpty()) {
            return;
        }
        List<AvailabilityRule> rules =
                availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(hostId);
        for (EventAvailabilityWindowRequest w : windows) {
            boolean contained = rules.stream().anyMatch(rule ->
                    rule.getDayOfWeek() == w.dayOfWeek()
                            && !w.startTime().isBefore(rule.getStartTime())
                            && !w.endTime().isAfter(rule.getEndTime()));
            if (!contained) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Availability window " + w.dayOfWeek() + " " + w.startTime() + "-" + w.endTime()
                                + " is not within the host's availability.");
            }
        }
    }

    private EventType requireOwnedDemandDrivenEventType(UUID requesterId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        if (eventType.getKind() == EventKind.GROUP) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Custom availability schedules are not supported for GROUP event types; "
                            + "use reservation windows instead.");
        }
        return eventType;
    }
}
