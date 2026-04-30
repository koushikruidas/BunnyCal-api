package com.daedalussystems.easySchedule.availability.repository;

import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, UUID> {

    List<AvailabilityRule> findByUserIdOrderByDayOfWeekAscStartTimeAsc(UUID userId);

    void deleteByUserId(UUID userId);
}
