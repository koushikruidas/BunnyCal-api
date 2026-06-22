package io.bunnycal.availability.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventAvailabilityWindow;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
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
 * Manages the recurring availability FILTER windows of a demand-driven event type
 * (ONE_ON_ONE, ROUND_ROBIN, COLLECTIVE).
 *
 * Crucially, this service NEVER writes to {@code availability_rules}: host-global
 * working hours are owned exclusively by {@link AvailabilityService}. These windows
 * only narrow the host's availability for the owning event type; they reserve no
 * time and block no other event type.
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
        return windowRepository.findByEventTypeId(eventTypeId).stream()
                .map(EventAvailabilityWindowResponse::from)
                .toList();
    }

    /**
     * Replaces the full set of availability filter windows for the event type (bulk
     * upsert). Unlike GROUP reservation windows there is NO cross-event overlap check
     * and NO self-overlap rejection -- a filter owns nothing, so overlapping filters
     * across event types (or even a redundant overlap within one type) are harmless.
     */
    @Transactional
    public List<EventAvailabilityWindowResponse> replaceWindows(UUID requesterId,
                                                                UUID eventTypeId,
                                                                List<EventAvailabilityWindowRequest> windows) {
        EventType eventType = requireOwnedDemandDrivenEventType(requesterId, eventTypeId);

        List<EventAvailabilityWindowRequest> safeWindows = windows == null ? List.of() : windows;
        validate(safeWindows);
        validateWithinHostAvailability(eventType.getUserId(), safeWindows);

        windowRepository.deleteByEventTypeId(eventTypeId);

        List<EventAvailabilityWindow> toSave = safeWindows.stream()
                .map(w -> EventAvailabilityWindow.builder()
                        .eventTypeId(eventTypeId)
                        .dayOfWeek(w.dayOfWeek())
                        .startTime(w.startTime())
                        .endTime(w.endTime())
                        .build())
                .toList();

        List<EventAvailabilityWindowResponse> saved = windowRepository.saveAll(toSave).stream()
                .map(EventAvailabilityWindowResponse::from)
                .toList();

        slotCacheVersionService.bumpVersionAfterCommit(eventType.getUserId());
        return saved;
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
     * Every filter window must fall within the host's global availability for that
     * day-of-week. Host availability is the upper bound: a filter can only narrow it,
     * never extend booking into hours the host is not open. (Slot generation enforces
     * this too via intersection, but rejecting at write time gives the host a clear
     * error instead of a window that silently produces no slots.)
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
                    "Availability filter windows are not supported for GROUP event types; "
                            + "use reservation windows instead.");
        }
        return eventType;
    }
}
