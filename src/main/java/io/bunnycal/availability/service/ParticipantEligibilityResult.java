package io.bunnycal.availability.service;

import java.util.UUID;

public record ParticipantEligibilityResult(UUID userId, boolean eligible, ParticipantEligibilityReason reason) {}
