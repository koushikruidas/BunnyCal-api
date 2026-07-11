package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a brand-new account the working hours the dashboard has always claimed it has.
 *
 * <p>The dashboard rendered Mon–Fri 09:00–17:00 for a new host, but that was React state only —
 * nothing wrote it, so {@code availability_rules} stayed empty until the host happened to open the
 * availability editor and press Save. Slot generation reads those rules, so until then the host's
 * public booking page offered nothing, while the dashboard insisted they were available 9–5.
 * Seeding at signup makes the displayed default the stored one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAvailabilityService {

    static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    static final LocalTime DEFAULT_END = LocalTime.of(17, 0);
    static final List<DayOfWeek> DEFAULT_DAYS = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY);

    private final AvailabilityRuleRepository availabilityRuleRepository;

    /**
     * Seeds Mon–Fri 09:00–17:00 for {@code userId}. No-op if the host already has any rule, so this
     * can never overwrite hours someone has set.
     */
    @Transactional
    public void seedFor(UUID userId) {
        if (!availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).isEmpty()) {
            return;
        }
        List<AvailabilityRule> rules = DEFAULT_DAYS.stream()
                .map(day -> AvailabilityRule.builder()
                        .userId(userId)
                        .dayOfWeek(day)
                        .startTime(DEFAULT_START)
                        .endTime(DEFAULT_END)
                        .build())
                .toList();
        availabilityRuleRepository.saveAll(rules);
        log.info("default_availability_seeded userId={} days={} start={} end={}",
                userId, DEFAULT_DAYS.size(), DEFAULT_START, DEFAULT_END);
    }
}
