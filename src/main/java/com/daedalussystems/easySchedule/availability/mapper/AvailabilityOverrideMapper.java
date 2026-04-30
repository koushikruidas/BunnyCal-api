package com.daedalussystems.easySchedule.availability.mapper;

import com.daedalussystems.easySchedule.availability.domain.AvailabilityOverride;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityOverrideMapper {

    public AvailabilityOverride toEntity(UUID userId, AvailabilityOverrideCreateRequest request) {
        return AvailabilityOverride.builder()
                .userId(userId)
                .date(request.getDate())
                .isAvailable(request.isAvailable())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .build();
    }

    public AvailabilityOverrideResponse toResponse(AvailabilityOverride override) {
        return AvailabilityOverrideResponse.builder()
                .id(override.getId())
                .date(override.getDate())
                .isAvailable(override.isAvailable())
                .startTime(override.getStartTime())
                .endTime(override.getEndTime())
                .build();
    }
}
