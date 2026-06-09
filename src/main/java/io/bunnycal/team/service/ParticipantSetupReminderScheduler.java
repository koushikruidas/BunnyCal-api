package io.bunnycal.team.service;

import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.team.domain.ParticipantSetupRequest;
import io.bunnycal.team.domain.TeamMember;
import io.bunnycal.team.notification.ParticipantSetupRequestNotificationService;
import io.bunnycal.team.notification.ParticipantSetupRequestOutboxPayload;
import io.bunnycal.team.repository.ParticipantSetupRequestRepository;
import io.bunnycal.team.repository.TeamMemberRepository;
import io.bunnycal.team.repository.TeamRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.team.domain.Team;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ParticipantSetupReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ParticipantSetupReminderScheduler.class);

    private final ParticipantSetupRequestRepository setupRequestRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;
    private final String frontendBaseUrl;

    public ParticipantSetupReminderScheduler(
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

    /** First reminder: 3 days after initial request, if not yet completed. */
    @Scheduled(cron = "0 0 9 * * *")
    @SchedulerLock(name = "participant_setup_first_reminder", lockAtMostFor = "PT10M")
    @Transactional
    public void sendFirstReminders() {
        Instant cutoff = Instant.now().minus(3, ChronoUnit.DAYS);
        var pending = setupRequestRepository.findPendingFirstReminder(cutoff);
        log.info("setup_reminder_first_pass count={}", pending.size());
        for (ParticipantSetupRequest req : pending) {
            publishReminder(req);
            req.setLastRemindedAt(Instant.now());
            req.setUpdatedAt(Instant.now());
            setupRequestRepository.save(req);
        }
    }

    /** Subsequent reminder: 7 days after the last reminder. */
    @Scheduled(cron = "0 30 9 * * *")
    @SchedulerLock(name = "participant_setup_subsequent_reminder", lockAtMostFor = "PT10M")
    @Transactional
    public void sendSubsequentReminders() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        var pending = setupRequestRepository.findPendingSubsequentReminder(cutoff);
        log.info("setup_reminder_subsequent_pass count={}", pending.size());
        for (ParticipantSetupRequest req : pending) {
            publishReminder(req);
            req.setLastRemindedAt(Instant.now());
            req.setUpdatedAt(Instant.now());
            setupRequestRepository.save(req);
        }
    }

    private void publishReminder(ParticipantSetupRequest req) {
        Team team = teamRepository.findById(req.getTeamId()).orElse(null);
        User target = userRepository.findById(req.getTargetUserId()).orElse(null);
        User owner  = userRepository.findById(req.getOwnerUserId()).orElse(null);

        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;

        ParticipantSetupRequestOutboxPayload payload = new ParticipantSetupRequestOutboxPayload(
                req.getId(),
                req.getTeamId(),
                team   != null ? team.getName()   : null,
                target != null ? target.getEmail() : null,
                target != null ? target.getName()  : null,
                owner  != null ? owner.getName()   : null,
                base + "/dashboard/availability"
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
