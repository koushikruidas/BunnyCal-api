package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.RoundRobinStatsResponse;
import io.bunnycal.availability.dto.RoundRobinStatsResponse.ParticipantStat;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.repository.BookingAssignmentRepository.ParticipantAssignmentStatsRow;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoundRobinStatsService {

    private final EventTypeRepository eventTypeRepository;
    private final EventTypeParticipantService participantService;
    private final BookingAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final ParticipantEligibilityService eligibilityService;

    public RoundRobinStatsService(
            EventTypeRepository eventTypeRepository,
            EventTypeParticipantService participantService,
            BookingAssignmentRepository assignmentRepository,
            UserRepository userRepository,
            ParticipantEligibilityService eligibilityService) {
        this.eventTypeRepository = eventTypeRepository;
        this.participantService = participantService;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.eligibilityService = eligibilityService;
    }

    @Transactional(readOnly = true)
    public RoundRobinStatsResponse getStats(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findById(eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        if (!eventType.getUserId().equals(actingUserId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "You do not own this event type.");
        }
        if (eventType.getKind() != EventKind.ROUND_ROBIN) {
            return new RoundRobinStatsResponse(0, 0, 0, List.of());
        }

        List<UUID> participantIds = participantService.effectiveParticipantUserIds(eventType);
        if (participantIds.isEmpty()) {
            return new RoundRobinStatsResponse(0, 0, 0, List.of());
        }

        Map<UUID, User> usersById = new HashMap<>();
        userRepository.findAllById(participantIds).forEach(u -> usersById.put(u.getId(), u));

        List<ParticipantAssignmentStatsRow> rows =
                assignmentRepository.findStatsForEventTypeAndParticipants(eventTypeId, participantIds);
        Map<UUID, ParticipantAssignmentStatsRow> statsByUser = new HashMap<>();
        rows.forEach(r -> statsByUser.put(r.getParticipantUserId(), r));

        int ready = 0;
        int needsSetup = 0;
        List<ParticipantStat> stats = new ArrayList<>();

        for (UUID uid : participantIds) {
            var eligibility = eligibilityService.checkForRoundRobin(uid);
            boolean hasCalendar = eligibilityService.hasActiveCalendar(uid);
            boolean hasWriteback = hasCalendar && eligibilityService.hasWritebackCapability(uid);

            ParticipantReadinessStatus status = switch (eligibility.reason()) {
                case USER_INACTIVE -> ParticipantReadinessStatus.INACTIVE;
                case USER_DELETED, USER_NOT_FOUND -> ParticipantReadinessStatus.REVOKED;
                case NO_AVAILABILITY_RULES -> ParticipantReadinessStatus.NO_AVAILABILITY;
                case NO_ACTIVE_CALENDAR -> ParticipantReadinessStatus.NO_CALENDAR;
                case ACTIVE -> {
                    if (!hasCalendar) yield ParticipantReadinessStatus.NO_CALENDAR;
                    if (!hasWriteback) yield ParticipantReadinessStatus.NO_WRITEBACK;
                    yield ParticipantReadinessStatus.READY;
                }
            };

            if (status == ParticipantReadinessStatus.READY) ready++;
            else needsSetup++;

            ParticipantAssignmentStatsRow row = statsByUser.get(uid);
            User u = usersById.get(uid);
            stats.add(new ParticipantStat(
                    uid.toString(),
                    u != null ? u.getName() : null,
                    u != null ? u.getEmail() : null,
                    status.name(),
                    row != null ? row.getAssignmentCount() : 0L,
                    row != null ? row.getLastAssignedAt() : null));
        }

        return new RoundRobinStatsResponse(participantIds.size(), ready, needsSetup, stats);
    }
}
