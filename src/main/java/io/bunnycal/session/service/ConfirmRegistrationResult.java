package io.bunnycal.session.service;

import java.util.UUID;

public record ConfirmRegistrationResult(
        UUID sessionId,
        UUID registrationId,
        UUID hostId,
        String capabilityToken
) {}
