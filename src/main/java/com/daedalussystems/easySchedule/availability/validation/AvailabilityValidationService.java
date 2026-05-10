package com.daedalussystems.easySchedule.availability.validation;

import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import com.daedalussystems.easySchedule.availability.dto.BulkAvailabilityRulesUpsertRequest;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimezoneService;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityValidationService {

    private static final int MAX_RULES_PER_DAY = 5;
    private final TimezoneService timezoneService;

    public AvailabilityValidationService(TimezoneService timezoneService) {
        this.timezoneService = timezoneService;
    }

    public void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "User timezone is required.");
        }
        try {
            timezoneService.resolveZone(timezone);
        } catch (Exception ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "User timezone must be a valid ZoneId.");
        }
    }

    public void validateBulkRules(BulkAvailabilityRulesUpsertRequest request) {
        List<AvailabilityRuleRequest> rules = request == null || request.getRules() == null ? List.of() : request.getRules();
        for (AvailabilityRuleRequest rule : rules) {
            validateRuleBasics(rule);
        }

        Map<DayOfWeek, List<AvailabilityRuleRequest>> byDay = rules.stream()
                .collect(Collectors.groupingBy(AvailabilityRuleRequest::getDayOfWeek));

        for (Map.Entry<DayOfWeek, List<AvailabilityRuleRequest>> entry : byDay.entrySet()) {
            List<AvailabilityRuleRequest> dayRules = new ArrayList<>(entry.getValue());
            if (dayRules.size() > MAX_RULES_PER_DAY) {
                throw new CustomException(
                        ErrorCode.VALIDATION_ERROR,
                        "Maximum 5 rules per day exceeded for " + entry.getKey() + ".");
            }

            dayRules.sort(Comparator.comparing(AvailabilityRuleRequest::getStartTime));
            for (int i = 1; i < dayRules.size(); i++) {
                AvailabilityRuleRequest previous = dayRules.get(i - 1);
                AvailabilityRuleRequest current = dayRules.get(i);
                if (current.getStartTime().isBefore(previous.getEndTime())) {
                    throw new CustomException(
                            ErrorCode.VALIDATION_ERROR,
                            "Overlapping availability rules detected for " + entry.getKey() + ".");
                }
            }
        }
    }

    public void validateOverride(AvailabilityOverrideCreateRequest request) {
        if (request == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Override payload is required.");
        }
        if (request.getDate() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Override date is required.");
        }

        if (!request.isAvailable()) {
            if (request.getStartTime() != null || request.getEndTime() != null) {
                throw new CustomException(
                        ErrorCode.VALIDATION_ERROR,
                        "startTime and endTime must be null when isAvailable is false.");
            }
            return;
        }

        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "startTime and endTime are required when isAvailable is true.");
        }
        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime must be before endTime.");
        }
    }

    private void validateRuleBasics(AvailabilityRuleRequest rule) {
        if (rule == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Rule entry cannot be null.");
        }
        if (rule.getDayOfWeek() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "dayOfWeek is required for each rule.");
        }
        if (rule.getStartTime() == null || rule.getEndTime() == null) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "startTime and endTime are required for each rule.");
        }
        if (!rule.getStartTime().isBefore(rule.getEndTime())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime must be before endTime.");
        }
    }
}
