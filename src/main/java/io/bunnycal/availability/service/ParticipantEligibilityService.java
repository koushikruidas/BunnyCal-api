package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Determines whether a participant is eligible to contribute slots to a ROUND_ROBIN
 * event type, and provides calendar readiness helpers.
 *
 * <p>Writeback capability is determined from {@code CalendarConnectionCalendar.canWrite},
 * which is populated by the inventory hydrator using provider access-role APIs
 * (Google: accessRole == writer/owner; Microsoft: canEdit). This is the single source
 * of truth for writeback and matches the logic used by projection scheduling.
 */
@Service
public class ParticipantEligibilityService {

    private final UserRepository userRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarConnectionCalendarRepository inventoryRepository;

    public ParticipantEligibilityService(
            UserRepository userRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            CalendarConnectionRepository calendarConnectionRepository,
            CalendarConnectionCalendarRepository inventoryRepository) {
        this.userRepository = userRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Checks whether {@code userId} is eligible to participate in ROUND_ROBIN slot
     * generation. Rules applied in order:
     * <ol>
     *   <li>User must exist.</li>
     *   <li>User must not be INACTIVE.</li>
     *   <li>User must not be DELETED.</li>
     *   <li>User must have at least one availability rule (no rules = no slots = excluded).</li>
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
     */
    public boolean hasActiveCalendar(UUID userId) {
        return !calendarConnectionRepository
                .findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE)
                .isEmpty();
    }

    /**
     * Returns {@code true} if the user has at least one ACTIVE calendar connection
     * with a writable calendar entry in the inventory.
     *
     * <p>Uses {@code CalendarConnectionCalendar.canWrite}, which is set from the
     * provider access-role API during inventory hydration — the same source of truth
     * used by projection scheduling. No scope string parsing.
     */
    public boolean hasWritebackCapability(UUID userId) {
        List<CalendarConnection> active = calendarConnectionRepository
                .findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE);
        if (active.isEmpty()) return false;
        List<UUID> connectionIds = active.stream().map(CalendarConnection::getId).toList();
        return inventoryRepository.existsByConnectionIdInAndCanWriteTrue(connectionIds);
    }

    /**
     * Returns {@code true} if the user is fully READY: active user account, has
     * availability rules, has an active calendar, and that calendar has writeback capability.
     *
     * <p>This is the single readiness gate used to decide when to auto-complete setup requests.
     */
    public boolean isReady(UUID userId) {
        ParticipantEligibilityResult eligibility = checkForRoundRobin(userId);
        if (!eligibility.eligible()) return false;
        boolean hasCalendar = hasActiveCalendar(userId);
        if (!hasCalendar) return false;
        return hasWritebackCapability(userId);
    }

    /**
     * Returns the provider name of the user's first ACTIVE calendar connection, or
     * {@code null} if none exist. Used for display-only hints in the UI.
     */
    public String activeCalendarProvider(UUID userId) {
        return calendarConnectionRepository
                .findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE)
                .stream()
                .findFirst()
                .map(c -> c.getProvider() != null ? c.getProvider().name() : null)
                .orElse(null);
    }
}
