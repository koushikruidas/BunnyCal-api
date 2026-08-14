package io.bunnycal.auth.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarConnectionManagementService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.Team;
import io.bunnycal.team.repository.TeamMemberRepository;
import io.bunnycal.team.repository.TeamRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class OnboardingServiceTest {
    @Mock UserRepository userRepository;
    @Mock AvailabilityRuleRepository availabilityRuleRepository;
    @Mock CalendarConnectionRepository connectionRepository;
    @Mock CalendarConnectionCalendarRepository calendarRepository;
    @Mock CalendarConnectionManagementService calendarManagementService;
    @Mock EventTypeRepository eventTypeRepository;
    @Mock TeamRepository teamRepository;
    @Mock TeamMemberRepository teamMemberRepository;

    private OnboardingService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OnboardingService(userRepository, availabilityRuleRepository, connectionRepository,
                calendarRepository, calendarManagementService, eventTypeRepository, teamRepository,
                teamMemberRepository, new SimpleMeterRegistry());
        user = User.builder().id(userId).email("new@example.com").name("New Host").timezone("UTC").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId)).thenReturn(List.of());
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.empty());
        when(eventTypeRepository.existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(userId, EventKind.ONE_ON_ONE))
                .thenReturn(false);
    }

    @Test
    void newAccountStartsAtPurposeWithAuthoritativeRequirementsMissing() {
        OnboardingStateResponse state = service.get(userId);

        assertThat(state.status()).isEqualTo(OnboardingStatus.NOT_STARTED);
        assertThat(state.resumeStep()).isEqualTo(OnboardingStep.PURPOSE);
        assertThat(state.missingRequirements()).containsExactly("availability", "calendar", "firstEvent");
    }

    @Test
    void availabilityCannotBeConfirmedWithoutAStoredRule() {
        assertThatThrownBy(() -> service.update(userId,
                new OnboardingUpdateRequest(OnboardingUseCase.CONSULTING, OnboardingStep.FIRST_EVENT, true, false)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Save at least one availability window");
    }

    @Test
    void completesOnlyWhenConfirmedHoursReadyCalendarAndPublishedOneToOneExist() throws Exception {
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(rule()));
        CalendarConnection connection = activeConnection();
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.of(connection));
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(readyCalendar()));
        when(eventTypeRepository.existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(userId, EventKind.ONE_ON_ONE))
                .thenReturn(true);

        OnboardingStateResponse state = service.update(userId,
                new OnboardingUpdateRequest(OnboardingUseCase.TEAM_MANAGEMENT, OnboardingStep.SUCCESS, true, false));

        assertThat(state.status()).isEqualTo(OnboardingStatus.COMPLETED);
        assertThat(state.resumeStep()).isEqualTo(OnboardingStep.SUCCESS);
        assertThat(state.missingRequirements()).isEmpty();
        assertThat(user.getAvailabilityConfirmedAt()).isNotNull();
        assertThat(user.getOnboardingCompletedAt()).isNotNull();
        verify(userRepository, atLeastOnce()).save(user);
    }

    /**
     * Someone who arrived by team invitation joined to receive team bookings, so a personal
     * one-on-one link is not something to demand of them before they can finish. The step is still
     * offered in the UI — it is only the completion requirement that relaxes.
     */
    @Test
    void invitedUserCompletesWithoutPublishingAPersonalLink() throws Exception {
        UUID teamId = UUID.randomUUID();
        user.setOnboardingInvitedTeamId(teamId);
        when(teamMemberRepository.existsActiveMembershipForUser(userId)).thenReturn(true);
        when(teamRepository.findByIdAndDeletedAtIsNull(teamId))
                .thenReturn(Optional.of(Team.builder().id(teamId).name("Customer Success").build()));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(rule()));
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId))
                .thenReturn(Optional.of(activeConnection()));
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(readyCalendar()));
        when(eventTypeRepository.existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(userId, EventKind.ONE_ON_ONE))
                .thenReturn(false);

        OnboardingStateResponse state = service.update(userId,
                new OnboardingUpdateRequest(OnboardingUseCase.TEAM_MANAGEMENT, OnboardingStep.SUCCESS, true, false));

        assertThat(state.status()).isEqualTo(OnboardingStatus.COMPLETED);
        assertThat(state.missingRequirements()).isEmpty();
        // Still reported, so the UI can offer the step — only the requirement relaxed.
        assertThat(state.firstEventReady()).isFalse();
        // Parking them on a step they may skip would read as being stuck.
        assertThat(state.resumeStep()).isEqualTo(OnboardingStep.SUCCESS);
        assertThat(state.invitedTeamId()).isEqualTo(teamId);
        assertThat(state.invitedTeamName()).isEqualTo("Customer Success");
    }

    /**
     * The counterpart, and the guard that this change stays scoped: for a user who signed up on
     * their own the booking link is the product, so the requirement is untouched.
     */
    @Test
    void selfSignedUpUserStillMustPublishAPersonalLink() throws Exception {
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(rule()));
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId))
                .thenReturn(Optional.of(activeConnection()));
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(readyCalendar()));
        when(eventTypeRepository.existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(userId, EventKind.ONE_ON_ONE))
                .thenReturn(false);

        OnboardingStateResponse state = service.update(userId,
                new OnboardingUpdateRequest(OnboardingUseCase.PERSONAL, OnboardingStep.FIRST_EVENT, true, false));

        assertThat(state.missingRequirements()).contains("firstEvent");
        assertThat(state.status()).isNotEqualTo(OnboardingStatus.COMPLETED);
        assertThat(state.resumeStep()).isEqualTo(OnboardingStep.FIRST_EVENT);
        assertThat(state.invitedTeamId()).isNull();
        assertThat(state.invitedTeamName()).isNull();
    }

    /**
     * A soft-deleted team has no name to show, and no longer relaxes the requirement either: the
     * exemption exists because team bookings are the reason the member is here, and a deleted team
     * sends none. Leaving it relaxed would let someone finish onboarding owning no bookable link at
     * all. The membership query filters deleted teams out, so this is the same call as never having
     * joined one.
     */
    @Test
    void invitedTeamThatWasDeletedResolvesToNoNameAndRestoresTheRequirement() throws Exception {
        UUID teamId = UUID.randomUUID();
        user.setOnboardingInvitedTeamId(teamId);
        when(teamRepository.findByIdAndDeletedAtIsNull(teamId)).thenReturn(Optional.empty());
        when(teamMemberRepository.existsActiveMembershipForUser(userId)).thenReturn(false);

        OnboardingStateResponse state = service.get(userId);

        assertThat(state.invitedTeamId()).isEqualTo(teamId);
        assertThat(state.invitedTeamName()).isNull();
        assertThat(state.missingRequirements()).contains("firstEvent");
    }

    /**
     * The bug this replaced invitedTeamId to fix. Someone who had signed up but never finished
     * onboarding, then accepted an invite, gets no invitedTeamId stamp — TeamService records that
     * only for accounts created by the invite. Keying the requirement off the stamp therefore
     * treated them as a solo signup and made publishing an event the price of escaping the
     * first-run gate, which is what the admin's calendar request had to fight through.
     */
    @Test
    void existingUserJoiningATeamNeedNotPublishEvenWithoutTheInviteStamp() throws Exception {
        assertThat(user.getOnboardingInvitedTeamId()).isNull();
        when(teamMemberRepository.existsActiveMembershipForUser(userId)).thenReturn(true);
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(rule()));
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId))
                .thenReturn(Optional.of(activeConnection()));
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(readyCalendar()));
        when(eventTypeRepository.existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(userId, EventKind.ONE_ON_ONE))
                .thenReturn(false);

        OnboardingStateResponse state = service.update(userId,
                new OnboardingUpdateRequest(OnboardingUseCase.TEAM_MANAGEMENT, OnboardingStep.SUCCESS, true, false));

        assertThat(state.missingRequirements()).doesNotContain("firstEvent");
        assertThat(state.status()).isEqualTo(OnboardingStatus.COMPLETED);
        // No stamp, so no team to name — the requirement relaxes on membership alone.
        assertThat(state.invitedTeamId()).isNull();
    }

    @Test
    void writableCalendarWithoutAvailabilitySelectionIsNotReady() throws Exception {
        user.setOnboardingUseCase(OnboardingUseCase.PERSONAL);
        user.setAvailabilityConfirmedAt(Instant.now());
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(rule()));
        CalendarConnection connection = activeConnection();
        CalendarConnectionCalendar calendar = readyCalendar();
        calendar.setChecksAvailability(false);
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.of(connection));
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(calendar));

        OnboardingStateResponse state = service.get(userId);

        assertThat(state.calendarReady()).isFalse();
        assertThat(state.resumeStep()).isEqualTo(OnboardingStep.FIRST_EVENT);
    }

    @Test
    void autoConfigureUsesPrimaryCalendarForAvailabilityWritebackAndGoogleMeet() throws Exception {
        CalendarConnection connection = activeConnection();
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        CalendarConnectionCalendar calendar = readyCalendar();
        calendar.setPrimary(true);
        calendar.setExternalCalendarId("primary@example.com");
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(calendar));

        service.configureCalendar(userId, connectionId);

        verify(calendarManagementService).setChecksAvailability(
                userId, connectionId, "primary@example.com", true);
        verify(calendarManagementService).setDefaultWriteback(userId, connectionId);
        verify(calendarManagementService).setWritebackCalendar(
                userId, connectionId, "primary@example.com");
        verify(calendarManagementService).setDefaultConferencing(
                userId, ConferencingProviderType.GOOGLE_MEET);
    }

    @Test
    void autoConfigureUsesNoNativeLinkForMicrosoftCalendarWithoutTeamsCapability() throws Exception {
        CalendarConnection connection = activeConnection();
        connection.setProvider(CalendarProviderType.MICROSOFT);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        CalendarConnectionCalendar calendar = readyCalendar();
        calendar.setPrimary(true);
        calendar.setExternalCalendarId("calendar-id");
        calendar.setSupportsNativeTeams(false);
        when(calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(calendar));

        service.configureCalendar(userId, connectionId);

        verify(calendarManagementService).setDefaultConferencing(userId, ConferencingProviderType.NONE);
    }

    private AvailabilityRule rule() {
        AvailabilityRule rule = new AvailabilityRule();
        rule.setUserId(userId);
        rule.setDayOfWeek(DayOfWeek.MONDAY);
        rule.setStartTime(LocalTime.of(9, 0));
        rule.setEndTime(LocalTime.of(17, 0));
        return rule;
    }

    private CalendarConnection activeConnection() throws Exception {
        CalendarConnection connection = new CalendarConnection();
        Field id = CalendarConnection.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(connection, connectionId);
        connection.setStatus(CalendarConnectionStatus.ACTIVE);
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setDefaultWriteback(true);
        return connection;
    }

    private CalendarConnectionCalendar readyCalendar() {
        CalendarConnectionCalendar calendar = new CalendarConnectionCalendar();
        calendar.setConnectionId(connectionId);
        calendar.setSelected(true);
        calendar.setChecksAvailability(true);
        calendar.setCanRead(true);
        calendar.setCanWrite(true);
        calendar.setHidden(false);
        return calendar;
    }
}
