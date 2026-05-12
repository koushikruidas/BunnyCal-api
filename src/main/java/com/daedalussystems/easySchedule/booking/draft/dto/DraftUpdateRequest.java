package com.daedalussystems.easySchedule.booking.draft.dto;

import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import java.util.List;

public record DraftUpdateRequest(
        String displayName,
        String timezone,
        String eventName,
        String description,
        String location,
        Integer durationMinutes,
        Integer slotIntervalMinutes,
        Integer holdDurationMinutes,
        List<AvailabilityRuleRequest> rules,
        List<AvailabilityOverrideCreateRequest> overrides
) {
}
