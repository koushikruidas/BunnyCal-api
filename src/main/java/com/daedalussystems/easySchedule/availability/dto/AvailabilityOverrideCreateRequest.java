package com.daedalussystems.easySchedule.availability.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvailabilityOverrideCreateRequest implements ForwardCompatibleRequest {
    private LocalDate date;
    private boolean isAvailable;
    private LocalTime startTime;
    private LocalTime endTime;
}
