package com.daedalussystems.easySchedule.booking.draft.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftCreateRequest(
        String email,
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
 ) implements ForwardCompatibleRequest {
}
