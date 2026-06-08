package io.bunnycal.availability.service;

import java.util.List;
import java.util.UUID;

/**
 * Internal diagnostic container for participant scheduling evaluation.
 */
public record ParticipantAvailabilityDiagnostics(List<ParticipantAvailabilityDiagnostic> participants) {

    public ParticipantAvailabilityDiagnostics {
        participants = List.copyOf(participants);
    }

    public List<ParticipantAvailabilityDiagnostic> eligibleParticipants() {
        return participants.stream()
                .filter(ParticipantAvailabilityDiagnostic::eligible)
                .toList();
    }

    public List<ParticipantAvailabilityDiagnostic> ineligibleParticipants() {
        return participants.stream()
                .filter(participant -> !participant.eligible())
                .toList();
    }

    public List<UUID> eligibleParticipantIds() {
        return eligibleParticipants().stream()
                .map(ParticipantAvailabilityDiagnostic::userId)
                .toList();
    }

    public boolean hasNoEligibleParticipants() {
        return eligibleParticipants().isEmpty();
    }

    public boolean hasCalendarMissingParticipant() {
        return eligibleParticipants().stream()
                .anyMatch(ParticipantAvailabilityDiagnostic::calendarMissing);
    }
}
