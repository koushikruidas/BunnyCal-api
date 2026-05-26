package io.bunnycal.availability.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.DayOfWeek;
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
public class AvailabilityRuleRequest implements ForwardCompatibleRequest {
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
}
