package io.bunnycal.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultAvailabilityServiceTest {

    @Mock
    private AvailabilityRuleRepository availabilityRuleRepository;

    @InjectMocks
    private DefaultAvailabilityService defaultAvailabilityService;

    @Test
    @SuppressWarnings("unchecked")
    void seedFor_createsMondayToFridayNineToFive() {
        UUID userId = UUID.randomUUID();
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of());

        defaultAvailabilityService.seedFor(userId);

        ArgumentCaptor<List<AvailabilityRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(availabilityRuleRepository).saveAll(captor.capture());
        List<AvailabilityRule> saved = captor.getValue();

        assertEquals(5, saved.size(), "weekdays only");
        Set<DayOfWeek> days = saved.stream().map(AvailabilityRule::getDayOfWeek).collect(Collectors.toSet());
        assertEquals(
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                days);
        assertTrue(saved.stream().allMatch(r -> r.getUserId().equals(userId)));
        assertTrue(saved.stream().allMatch(r -> r.getStartTime().equals(LocalTime.of(9, 0))));
        assertTrue(saved.stream().allMatch(r -> r.getEndTime().equals(LocalTime.of(17, 0))));
    }

    /** Seeding must never overwrite hours a host has already set. */
    @Test
    void seedFor_isNoopWhenRulesAlreadyExist() {
        UUID userId = UUID.randomUUID();
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(AvailabilityRule.builder()
                        .userId(userId)
                        .dayOfWeek(DayOfWeek.SATURDAY)
                        .startTime(LocalTime.of(11, 0))
                        .endTime(LocalTime.of(13, 0))
                        .build()));

        defaultAvailabilityService.seedFor(userId);

        verify(availabilityRuleRepository, never()).saveAll(any());
    }
}
