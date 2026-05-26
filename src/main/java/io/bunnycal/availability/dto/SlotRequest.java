package io.bunnycal.availability.dto;

import java.time.LocalDate;
import java.util.UUID;

public record SlotRequest(UUID userId, UUID eventTypeId, LocalDate date) {}
