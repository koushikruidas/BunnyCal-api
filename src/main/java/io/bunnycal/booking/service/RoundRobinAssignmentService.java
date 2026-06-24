package io.bunnycal.booking.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotUtc;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.availability.service.ParticipantAvailabilityService;
import io.bunnycal.availability.service.ParticipantEligibilityResult;
import io.bunnycal.availability.service.ParticipantEligibilityService;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoundRobinAssignmentService {

    private final EventTypeParticipantService participantService;
    private final ParticipantEligibilityService eligibilityService;
    private final ParticipantAvailabilityService participantAvailabilityService;
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final BookingService bookingService;
    private final UserRepository userRepository;

    public RoundRobinAssignmentService(EventTypeParticipantService participantService,
                                       ParticipantEligibilityService eligibilityService,
                                       ParticipantAvailabilityService participantAvailabilityService,
                                       BookingAssignmentRepository bookingAssignmentRepository,
                                       BookingService bookingService,
                                       UserRepository userRepository) {
        this.participantService = participantService;
        this.eligibilityService = eligibilityService;
        this.participantAvailabilityService = participantAvailabilityService;
        this.bookingAssignmentRepository = bookingAssignmentRepository;
        this.bookingService = bookingService;
        this.userRepository = userRepository;
    }

    public Map<UUID, AssignmentStats> statsForParticipants(UUID eventTypeId, Collection<UUID> participantIds) {
        Map<UUID, AssignmentStats> stats = new HashMap<>();
        for (UUID participantId : participantIds) {
            stats.put(participantId, new AssignmentStats(0L, null));
        }
        if (participantIds.isEmpty()) {
            return stats;
        }
        bookingAssignmentRepository.findStatsForEventTypeAndParticipants(eventTypeId, participantIds)
                .forEach(row -> stats.put(
                        row.getParticipantUserId(),
                        new AssignmentStats(row.getAssignmentCount(), row.getLastAssignedAt())));
        return stats;
    }

    public List<UUID> candidateParticipantsForSlot(EventType eventType,
                                                   Instant start,
                                                   Instant end,
                                                   Collection<UUID> participantIds,
                                                   Instant now) {
        List<UUID> candidates = new ArrayList<>();
        Set<UUID> allowed = participantIds == null ? Set.of() : new HashSet<>(participantIds);
        for (UUID participantId : participantService.effectiveParticipantUserIds(eventType)) {
            if (!allowed.isEmpty() && !allowed.contains(participantId)) {
                continue;
            }
            ParticipantEligibilityResult eligibility = eligibilityService.checkForRoundRobin(participantId);
            if (!eligibility.eligible()) {
                continue;
            }
            User participant = userRepository.findById(participantId)
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Participant user not found."));
            LocalDate localDate = start.atZone(java.time.ZoneId.of(participant.getTimezone())).toLocalDate();
            boolean available = participantAvailabilityService.computeForParticipant(participantId, eventType, localDate, now)
                    .stream()
                    .anyMatch(slot -> slot.start().equals(start) && slot.end().equals(end));
            if (available) {
                candidates.add(participantId);
            }
        }
        return candidates;
    }

    @Transactional
    public AssignedRoundRobinBooking assignAndCreateHeldBooking(EventType eventType,
                                                                Instant start,
                                                                Instant end,
                                                                List<UUID> signedCandidateIds,
                                                                java.time.Duration holdDuration,
                                                                String guestEmail,
                                                                String guestName) {
        return assignAndCreateHeldBooking(
                eventType,
                start,
                end,
                signedCandidateIds,
                holdDuration,
                guestEmail,
                guestName,
                null,
                null,
                null);
    }

    @Transactional
    public AssignedRoundRobinBooking assignAndCreateHeldBooking(EventType eventType,
                                                                Instant start,
                                                                Instant end,
                                                                List<UUID> signedCandidateIds,
                                                                java.time.Duration holdDuration,
                                                                String guestEmail,
                                                                String guestName,
                                                                String guestNotes,
                                                                AuthProvider inviteeAuthProvider,
                                                                String inviteeProviderUserId) {
        Instant now = Instant.now();
        List<UUID> currentCandidates = candidateParticipantsForSlot(eventType, start, end, signedCandidateIds, now);
        if (currentCandidates.isEmpty()) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        Map<UUID, Integer> signedOrder = new HashMap<>();
        for (int i = 0; i < signedCandidateIds.size(); i++) {
            signedOrder.put(signedCandidateIds.get(i), i);
        }
        Map<UUID, AssignmentStats> stats = statsForParticipants(eventType.getId(), currentCandidates);
        currentCandidates.sort(Comparator
                .comparing((UUID id) -> stats.getOrDefault(id, new AssignmentStats(0L, null)).lastAssignedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(id -> signedOrder.getOrDefault(id, Integer.MAX_VALUE))
                .thenComparing(UUID::toString));

        CustomException lastConflict = null;
        for (UUID participantId : currentCandidates) {
            try {
                Booking booking = guestNotes == null && inviteeAuthProvider == null && inviteeProviderUserId == null
                        ? bookingService.createHeldBooking(
                                participantId,
                                eventType.getId(),
                                start,
                                end,
                                holdDuration,
                                guestEmail,
                                guestName)
                        : bookingService.createHeldBooking(
                                participantId,
                                eventType.getId(),
                                start,
                                end,
                                holdDuration,
                                guestEmail,
                                guestName,
                                guestNotes,
                                inviteeAuthProvider,
                                inviteeProviderUserId);
                bookingAssignmentRepository.save(BookingAssignment.builder()
                        .bookingId(booking.getId())
                        .participantUserId(participantId)
                        .assignmentReason(AssignmentReason.LEAST_RECENTLY_ASSIGNED)
                        .build());
                return new AssignedRoundRobinBooking(booking, participantId, AssignmentReason.LEAST_RECENTLY_ASSIGNED);
            } catch (CustomException ex) {
                if (ex.getErrorCode() == ErrorCode.SLOT_ALREADY_BOOKED
                        || ex.getErrorCode() == ErrorCode.SLOT_UNAVAILABLE
                        || ex.getErrorCode() == ErrorCode.TOO_MANY_PENDING_BOOKINGS) {
                    lastConflict = ex;
                    continue;
                }
                throw ex;
            }
        }
        if (lastConflict != null) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
    }

    public record AssignmentStats(long assignmentCount, Instant lastAssignedAt) {
    }

    public record AssignedRoundRobinBooking(
            Booking booking,
            UUID participantUserId,
            AssignmentReason assignmentReason) {
    }
}
