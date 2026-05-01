package com.daedalussystems.easySchedule.availability.dto;

import java.time.Instant;

public record SlotDto(String slotId, Instant start, Instant end) {}
