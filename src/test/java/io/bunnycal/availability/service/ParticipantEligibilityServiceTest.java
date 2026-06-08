package io.bunnycal.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.UserStatus;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ParticipantEligibilityService} — no Spring context.
 *
 * <p>Test cases A-E per spec:
 * <ul>
 *   <li>A: ACTIVE + rules → eligible, reason=ACTIVE</li>
 *   <li>B: INACTIVE → ineligible, reason=USER_INACTIVE</li>
 *   <li>C: DELETED → ineligible, reason=USER_DELETED</li>
 *   <li>D: ACTIVE + no rules → ineligible, reason=NO_AVAILABILITY_RULES</li>
 *   <li>E: ACTIVE + rules + no calendar → eligible (calendar not required for RR);
 *          hasActiveCalendar() returns false</li>
 * </ul>
 */
class ParticipantEligibilityServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AvailabilityRuleRepository availabilityRuleRepository;
    @Mock private CalendarConnectionRepository calendarConnectionRepository;

    private ParticipantEligibilityService service;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ParticipantEligibilityService(
                userRepository, availabilityRuleRepository, calendarConnectionRepository);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User userWithStatus(UserStatus status) {
        return User.builder()
                .id(userId)
                .email("p@example.com")
                .name("Participant")
                .timezone("UTC")
                .status(status)
                .build();
    }

    private AvailabilityRule mondayRule() {
        AvailabilityRule rule = new AvailabilityRule();
        rule.setId(UUID.randomUUID());
        rule.setUserId(userId);
        rule.setDayOfWeek(DayOfWeek.MONDAY);
        rule.setStartTime(LocalTime.of(9, 0));
        rule.setEndTime(LocalTime.of(17, 0));
        return rule;
    }

    // ── Test A: active user with rules → eligible ──────────────────────────────

    @Test
    void testA_activeUser_withRules_isEligible() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.ACTIVE)));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule()));

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.ACTIVE);
        assertThat(result.userId()).isEqualTo(userId);
    }

    // ── Test B: inactive user → ineligible ────────────────────────────────────

    @Test
    void testB_inactiveUser_isIneligible_withUserInactiveReason() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.INACTIVE)));

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.USER_INACTIVE);
        assertThat(result.userId()).isEqualTo(userId);
    }

    // ── Test C: deleted user → ineligible ─────────────────────────────────────

    @Test
    void testC_deletedUser_isIneligible_withUserDeletedReason() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.DELETED)));

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.USER_DELETED);
        assertThat(result.userId()).isEqualTo(userId);
    }

    // ── Test D: active user + no rules → ineligible ───────────────────────────

    @Test
    void testD_activeUser_noRules_isIneligible_withNoAvailabilityRulesReason() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.ACTIVE)));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of());

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.NO_AVAILABILITY_RULES);
        assertThat(result.userId()).isEqualTo(userId);
    }

    // ── Test E: active user + rules + no calendar → eligible (calendar not required for RR) ──

    @Test
    void testE_activeUser_rulesButNoCalendar_isEligible_butHasActiveCalendarReturnsFalse() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.ACTIVE)));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule()));
        when(calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());

        // Eligibility check does NOT require a calendar for RR.
        ParticipantEligibilityResult eligibility = service.checkForRoundRobin(userId);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.reason()).isEqualTo(ParticipantEligibilityReason.ACTIVE);

        // Calendar check returns false (no active connections).
        boolean hasCalendar = service.hasActiveCalendar(userId);
        assertThat(hasCalendar).isFalse();
    }

    // ── User not found → ineligible ───────────────────────────────────────────

    @Test
    void userNotFound_isIneligible_withUserNotFoundReason() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.USER_NOT_FOUND);
        assertThat(result.userId()).isEqualTo(userId);
    }

    // ── hasActiveCalendar returns true when connection exists ─────────────────

    @Test
    void hasActiveCalendar_returnsTrue_whenActiveConnectionExists() {
        CalendarConnection conn = mock(CalendarConnection.class);
        when(calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));

        assertThat(service.hasActiveCalendar(userId)).isTrue();
    }
}
