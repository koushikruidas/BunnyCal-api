package com.daedalussystems.easySchedule.availability.mapper;

import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityRuleMapper {

    public AvailabilityRule toEntity(UUID userId, AvailabilityRuleRequest request) {
        return AvailabilityRule.builder()
                .userId(userId)
                .dayOfWeek(request.getDayOfWeek())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .build();
    }

    public AvailabilityRuleResponse toResponse(AvailabilityRule rule) {
        return AvailabilityRuleResponse.builder()
                .id(rule.getId())
                .dayOfWeek(rule.getDayOfWeek())
                .startTime(rule.getStartTime())
                .endTime(rule.getEndTime())
                .build();
    }
}
