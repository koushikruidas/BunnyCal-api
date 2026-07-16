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

    private OnboardingService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OnboardingService(userRepository, availabilityRuleRepository, connectionRepository,
                calendarRepository, calendarManagementService, eventTypeRepository, new SimpleMeterRegistry());
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
                new OnboardingUpdateRequest(OnboardingUseCase.CONSULTING, OnboardingStep.CALENDAR, true, false)))
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
        assertThat(state.resumeStep()).isEqualTo(OnboardingStep.CALENDAR);
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
