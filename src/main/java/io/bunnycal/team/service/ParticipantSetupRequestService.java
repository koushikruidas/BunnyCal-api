package io.bunnycal.team.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.ParticipantSetupRequest;
import io.bunnycal.team.domain.Team;
import io.bunnycal.team.domain.TeamMember;
import io.bunnycal.team.dto.SetupStatusResponse;
import io.bunnycal.team.notification.ParticipantSetupRequestNotificationService;
import io.bunnycal.team.notification.ParticipantSetupRequestOutboxPayload;
import io.bunnycal.team.repository.ParticipantSetupRequestRepository;
import io.bunnycal.team.repository.TeamMemberRepository;
import io.bunnycal.team.repository.TeamRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipantSetupRequestService {

    private static final Duration RESEND_COOLDOWN = Duration.ofHours(24);

    private final ParticipantSetupRequestRepository setupRequestRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;
    private final String frontendBaseUrl;

    public ParticipantSetupRequestService(
            ParticipantSetupRequestRepository setupRequestRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            OutboxPublisher outboxPublisher,
            @Value("${app.public-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.setupRequestRepository = setupRequestRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.outboxPublisher = outboxPublisher;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Transactional
    public SetupStatusResponse sendSetupRequest(UUID ownerUserId, UUID teamMemberId) {
        TeamMember member = teamMemberRepository.findById(teamMemberId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Team member not found."));

        if (!teamMemberRepository.existsByTeamIdAndUserId(member.getTeamId(), ownerUserId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "You are not a member of this team.");
        }

        if (member.getUserId().equals(ownerUserId)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Cannot send a setup request to yourself.");
        }

        Instant now = Instant.now();

        ParticipantSetupRequest req = setupRequestRepository
                .findByOwnerUserIdAndTargetUserId(ownerUserId, member.getUserId())
                .orElse(null);

        if (req != null) {
            Instant lastSent = req.getLastRemindedAt() != null ? req.getLastRemindedAt() : req.getRequestedAt();
            if (lastSent != null && now.isBefore(lastSent.plus(RESEND_COOLDOWN))) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "A setup request was sent recently. Please wait 24 hours before sending another.");
            }
            req.setStatus("REQUESTED");
            req.setLastRemindedAt(req.getRequestedAt() != null ? now : null);
            if (req.getRequestedAt() == null) req.setRequestedAt(now);
            else req.setLastRemindedAt(now);
            req.setUpdatedAt(now);
        } else {
            req = setupRequestRepository.save(ParticipantSetupRequest.builder()
                    .ownerUserId(ownerUserId)
                    .targetUserId(member.getUserId())
                    .teamId(member.getTeamId())
                    .teamMemberId(teamMemberId)
                    .status("REQUESTED")
                    .requestedAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        setupRequestRepository.save(req);

        publishSetupRequestEvent(req, member);

        boolean canResend = false;
        return new SetupStatusResponse("REQUESTED", req.getRequestedAt(), req.getLastRemindedAt(), canResend);
    }

    @Transactional(readOnly = true)
    public SetupStatusResponse getSetupStatus(UUID requestingUserId, UUID teamMemberId) {
        TeamMember member = teamMemberRepository.findById(teamMemberId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Team member not found."));

        if (!teamMemberRepository.existsByTeamIdAndUserId(member.getTeamId(), requestingUserId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "You are not a member of this team.");
        }

        ParticipantSetupRequest req = setupRequestRepository
                .findByOwnerUserIdAndTargetUserId(requestingUserId, member.getUserId())
                .orElse(null);

        if (req == null) {
            return new SetupStatusResponse("NOT_STARTED", null, null, true);
        }

        Instant lastSent = req.getLastRemindedAt() != null ? req.getLastRemindedAt() : req.getRequestedAt();
        boolean canResend = lastSent == null || Instant.now().isAfter(lastSent.plus(RESEND_COOLDOWN));
        return new SetupStatusResponse(req.getStatus(), req.getRequestedAt(), req.getLastRemindedAt(), canResend);
    }

    @Transactional
    public void markCompleted(UUID ownerUserId, UUID targetUserId) {
        setupRequestRepository.findByOwnerUserIdAndTargetUserId(ownerUserId, targetUserId)
                .ifPresent(req -> {
                    req.setStatus("COMPLETED");
                    req.setUpdatedAt(Instant.now());
                    setupRequestRepository.save(req);
                });
    }

    /**
     * Completes all open setup requests for {@code targetUserId} regardless of which
     * owner issued them. Called when a participant becomes fully ready (calendar connected
     * or availability rules saved) so all outstanding requests are closed automatically.
     */
    @Transactional
    public void markAllCompletedForTarget(UUID targetUserId) {
        Instant now = Instant.now();
        setupRequestRepository.findByTargetUserId(targetUserId).stream()
                .filter(req -> "REQUESTED".equals(req.getStatus()))
                .forEach(req -> {
                    req.setStatus("COMPLETED");
                    req.setUpdatedAt(now);
                    setupRequestRepository.save(req);
                });
    }

    private void publishSetupRequestEvent(ParticipantSetupRequest req, TeamMember member) {
        Team team = teamRepository.findByIdAndDeletedAtIsNull(req.getTeamId()).orElse(null);
        User target = userRepository.findById(req.getTargetUserId()).orElse(null);
        User owner  = userRepository.findById(req.getOwnerUserId()).orElse(null);

        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        String setupUrl = base + "/dashboard/availability";

        ParticipantSetupRequestOutboxPayload payload = new ParticipantSetupRequestOutboxPayload(
                req.getId(),
                req.getTeamId(),
                team != null ? team.getName() : null,
                target != null ? target.getEmail() : null,
                target != null ? target.getName() : null,
                owner  != null ? owner.getName()  : null,
                setupUrl
        );

        outboxPublisher.publish(
                ParticipantSetupRequestNotificationService.AGGREGATE_TYPE,
                req.getId(),
                new OutboxPayloadEnvelope(
                        UUID.randomUUID().toString(),
                        ParticipantSetupRequestNotificationService.EVENT_TYPE,
                        1,
                        payload));
    }
}
