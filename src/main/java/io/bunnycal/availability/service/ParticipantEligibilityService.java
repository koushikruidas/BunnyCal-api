package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.UserStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Determines whether a participant is eligible to contribute slots to a ROUND_ROBIN
 * event type. Calendar is NOT required for RR eligibility — calendar-less participants
 * are eligible but will degrade the response.
 */
@Service
public class ParticipantEligibilityService {

    private final UserRepository userRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;

    public ParticipantEligibilityService(
            UserRepository userRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            CalendarConnectionRepository calendarConnectionRepository) {
        this.userRepository = userRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
    }

    /**
     * Checks whether {@code userId} is eligible to participate in ROUND_ROBIN slot
     * generation. Rules applied in order:
     * <ol>
     *   <li>User must exist.</li>
     *   <li>User must not be INACTIVE.</li>
     *   <li>User must not be DELETED.</li>
     *   <li>User must have at least one availability rule (no rules = no slots = excluded).</li>
     *   <li>Calendar is NOT required — calendar-less participants are eligible but degrade
     *       the response (caller checks {@link #hasActiveCalendar(UUID)} separately).</li>
     * </ol>
     */
    public ParticipantEligibilityResult checkForRoundRobin(UUID userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return new ParticipantEligibilityResult(userId, false, ParticipantEligibilityReason.USER_NOT_FOUND);
        }
        User user = userOpt.get();

        if (user.getStatus() == UserStatus.INACTIVE) {
            return new ParticipantEligibilityResult(userId, false, ParticipantEligibilityReason.USER_INACTIVE);
        }
        if (user.getStatus() == UserStatus.DELETED) {
            return new ParticipantEligibilityResult(userId, false, ParticipantEligibilityReason.USER_DELETED);
        }

        boolean hasRules = !availabilityRuleRepository
                .findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId)
                .isEmpty();
        if (!hasRules) {
            return new ParticipantEligibilityResult(userId, false, ParticipantEligibilityReason.NO_AVAILABILITY_RULES);
        }

        return new ParticipantEligibilityResult(userId, true, ParticipantEligibilityReason.ACTIVE);
    }

    /**
     * Returns {@code true} if the user has at least one ACTIVE calendar connection.
     * Used to determine whether the RR response should be degraded.
     */
    public boolean hasActiveCalendar(UUID userId) {
        return !calendarConnectionRepository
                .findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE)
                .isEmpty();
    }
}
