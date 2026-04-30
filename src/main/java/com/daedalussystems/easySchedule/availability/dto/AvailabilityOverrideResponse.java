package com.daedalussystems.easySchedule.availability.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
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
public class AvailabilityOverrideResponse {

    private UUID id;
    private LocalDate date;
    private boolean isAvailable;
    private LocalTime startTime;
    private LocalTime endTime;
}
