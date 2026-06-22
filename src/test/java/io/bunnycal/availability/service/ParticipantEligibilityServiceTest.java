package io.bunnycal.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
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
 * <p>Test cases A–E from original spec plus writeback and isReady coverage:
 * <ul>
 *   <li>A: ACTIVE + rules → eligible, reason=ACTIVE</li>
 *   <li>B: INACTIVE → ineligible, reason=USER_INACTIVE</li>
 *   <li>C: DELETED → ineligible, reason=USER_DELETED</li>
 *   <li>D: ACTIVE + no rules → ineligible, reason=NO_AVAILABILITY_RULES</li>
 *   <li>E: ACTIVE + rules + no calendar → eligible; hasActiveCalendar=false</li>
 *   <li>F: hasWritebackCapability → true when inventory has canWrite entry</li>
 *   <li>G: hasWritebackCapability → false when no active connections</li>
 *   <li>H: hasWritebackCapability → false when inventory has no canWrite entry</li>
 *   <li>I: isReady → true only when all three dimensions satisfied</li>
 *   <li>J: isReady → false when availability missing (even with calendar + writeback)</li>
 *   <li>K: isReady → false when calendar missing (even with availability)</li>
 *   <li>L: isReady → false when writeback missing (has calendar, no canWrite)</li>
 * </ul>
 */
class ParticipantEligibilityServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AvailabilityRuleRepository availabilityRuleRepository;
    @Mock private CalendarConnectionRepository calendarConnectionRepository;
    @Mock private CalendarConnectionCalendarRepository inventoryRepository;

    private ParticipantEligibilityService service;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID connectionId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ParticipantEligibilityService(
                userRepository, availabilityRuleRepository,
                calendarConnectionRepository, inventoryRepository);
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

    private CalendarConnection activeConnection() {
        CalendarConnection conn = new CalendarConnection();
        try {
            java.lang.reflect.Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(conn, connectionId);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return conn;
    }

    private void stubActiveUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.ACTIVE)));
    }

    private void stubRules() {
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule()));
    }

    private void stubActiveCalendar() {
        when(calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(activeConnection()));
    }

    private void stubNoCalendar() {
        when(calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
    }

    // ── Test A ─────────────────────────────────────────────────────────────────

    @Test
    void testA_activeUser_withRules_isEligible() {
        stubActiveUser();
        stubRules();

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.ACTIVE);
        assertThat(result.userId()).isEqualTo(userId);
    }

    // ── Test B ─────────────────────────────────────────────────────────────────

    @Test
    void testB_inactiveUser_isIneligible() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.INACTIVE)));

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.USER_INACTIVE);
    }

    // ── Test C ─────────────────────────────────────────────────────────────────

    @Test
    void testC_deletedUser_isIneligible() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithStatus(UserStatus.DELETED)));

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.USER_DELETED);
    }

    // ── Test D ─────────────────────────────────────────────────────────────────

    @Test
    void testD_activeUser_noRules_isIneligible() {
        stubActiveUser();
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of());

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.NO_AVAILABILITY_RULES);
    }

    // ── Test E ─────────────────────────────────────────────────────────────────

    @Test
    void testE_activeUser_rulesButNoCalendar_isEligibleButCalendarFalse() {
        stubActiveUser();
        stubRules();
        stubNoCalendar();

        ParticipantEligibilityResult eligibility = service.checkForRoundRobin(userId);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.reason()).isEqualTo(ParticipantEligibilityReason.ACTIVE);

        assertThat(service.hasActiveCalendar(userId)).isFalse();
    }

    // ── User not found ─────────────────────────────────────────────────────────

    @Test
    void userNotFound_isIneligible() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        ParticipantEligibilityResult result = service.checkForRoundRobin(userId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(ParticipantEligibilityReason.USER_NOT_FOUND);
    }

    // ── hasActiveCalendar ──────────────────────────────────────────────────────

    @Test
    void hasActiveCalendar_returnsTrue_whenActiveConnectionExists() {
        when(calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(activeConnection()));

        assertThat(service.hasActiveCalendar(userId)).isTrue();
    }

    // ── Test F: hasWritebackCapability — inventory has canWrite row ────────────

    @Test
    void testF_hasWritebackCapability_trueWhenInventoryHasCanWriteEntry() {
        stubActiveCalendar();
        when(inventoryRepository.existsByConnectionIdInAndCanWriteTrue(List.of(connectionId)))
                .thenReturn(true);

        assertThat(service.hasWritebackCapability(userId)).isTrue();
    }

    // ── Test G: hasWritebackCapability — no active connections ────────────────

    @Test
    void testG_hasWritebackCapability_falseWhenNoActiveCalendar() {
        stubNoCalendar();

        assertThat(service.hasWritebackCapability(userId)).isFalse();
    }

    // ── Test H: hasWritebackCapability — connection exists but read-only ───────

    @Test
    void testH_hasWritebackCapability_falseWhenInventoryHasNoCanWriteEntry() {
        stubActiveCalendar();
        when(inventoryRepository.existsByConnectionIdInAndCanWriteTrue(List.of(connectionId)))
                .thenReturn(false);

        assertThat(service.hasWritebackCapability(userId)).isFalse();
    }

    // ── Test I: isReady — all three dimensions satisfied ──────────────────────

    @Test
    void testI_isReady_trueWhenAllThreeDimensionsSatisfied() {
        stubActiveUser();
        stubRules();
        stubActiveCalendar();
        when(inventoryRepository.existsByConnectionIdInAndCanWriteTrue(List.of(connectionId)))
                .thenReturn(true);

        assertThat(service.isReady(userId)).isTrue();
    }

    // ── Test J: isReady — availability missing ────────────────────────────────

    @Test
    void testJ_isReady_falseWhenAvailabilityMissing() {
        stubActiveUser();
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of());
        // calendar and writeback present, but eligibility fails first
        stubActiveCalendar();
        when(inventoryRepository.existsByConnectionIdInAndCanWriteTrue(List.of(connectionId)))
                .thenReturn(true);

        assertThat(service.isReady(userId)).isFalse();
    }

    // ── Test K: isReady — calendar missing ───────────────────────────────────

    @Test
    void testK_isReady_falseWhenCalendarMissing() {
        stubActiveUser();
        stubRules();
        stubNoCalendar();

        assertThat(service.isReady(userId)).isFalse();
    }

    // ── Test L: isReady — writeback missing (read-only calendar) ─────────────

    @Test
    void testL_isReady_falseWhenWritebackMissing() {
        stubActiveUser();
        stubRules();
        stubActiveCalendar();
        when(inventoryRepository.existsByConnectionIdInAndCanWriteTrue(List.of(connectionId)))
                .thenReturn(false);

        assertThat(service.isReady(userId)).isFalse();
    }
}
