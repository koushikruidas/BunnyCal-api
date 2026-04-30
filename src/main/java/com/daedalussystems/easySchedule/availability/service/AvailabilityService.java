package com.daedalussystems.easySchedule.availability.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityOverride;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideResponse;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleResponse;
import com.daedalussystems.easySchedule.availability.dto.BulkAvailabilityRulesUpsertRequest;
import com.daedalussystems.easySchedule.availability.mapper.AvailabilityOverrideMapper;
import com.daedalussystems.easySchedule.availability.mapper.AvailabilityRuleMapper;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityOverrideRepository;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityRuleRepository;
import com.daedalussystems.easySchedule.availability.validation.AvailabilityValidationService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final AvailabilityRuleMapper availabilityRuleMapper;
    private final AvailabilityOverrideMapper availabilityOverrideMapper;
    private final AvailabilityValidationService validationService;
    private final UserRepository userRepository;

    public AvailabilityService(
            AvailabilityRuleRepository availabilityRuleRepository,
            AvailabilityOverrideRepository availabilityOverrideRepository,
            AvailabilityRuleMapper availabilityRuleMapper,
            AvailabilityOverrideMapper availabilityOverrideMapper,
            AvailabilityValidationService validationService,
            UserRepository userRepository) {
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.availabilityRuleMapper = availabilityRuleMapper;
        this.availabilityOverrideMapper = availabilityOverrideMapper;
        this.validationService = validationService;
        this.userRepository = userRepository;
    }

    @Transactional
    public List<AvailabilityRuleResponse> replaceRules(UUID userId, BulkAvailabilityRulesUpsertRequest request) {
        validationService.validateBulkRules(request);
        ensureUserTimezone(userId);

        availabilityRuleRepository.deleteByUserId(userId);

        List<AvailabilityRule> toSave = (request.getRules() == null ? List.<AvailabilityRule>of() : request.getRules().stream()
                .map(rule -> availabilityRuleMapper.toEntity(userId, rule))
                .toList());

        if (toSave.isEmpty()) {
            return List.of();
        }

        return availabilityRuleRepository.saveAll(toSave).stream()
                .map(availabilityRuleMapper::toResponse)
                .toList();
    }

    @Transactional
    public AvailabilityOverrideResponse createOverride(UUID userId, AvailabilityOverrideCreateRequest request) {
        validationService.validateOverride(request);
        ensureUserTimezone(userId);

        if (availabilityOverrideRepository.existsByUserIdAndDate(userId, request.getDate())) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "An override already exists for date " + request.getDate() + ".");
        }

        AvailabilityOverride saved = availabilityOverrideRepository.save(availabilityOverrideMapper.toEntity(userId, request));
        return availabilityOverrideMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<AvailabilityOverrideResponse> getOverrides(UUID userId, LocalDate from, LocalDate to) {
        ensureUserTimezone(userId);

        LocalDate effectiveFrom = from == null ? LocalDate.now() : from;
        LocalDate effectiveTo = to == null ? effectiveFrom.plusDays(30) : to;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "'to' date must be on or after 'from' date.");
        }

        return availabilityOverrideRepository.findByUserIdAndDateBetweenOrderByDateAsc(userId, effectiveFrom, effectiveTo).stream()
                .map(availabilityOverrideMapper::toResponse)
                .toList();
    }

    @Transactional
    public void deleteOverride(UUID userId, UUID overrideId) {
        ensureUserTimezone(userId);

        AvailabilityOverride override = availabilityOverrideRepository
                .findByIdAndUserId(overrideId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Override not found."));
        availabilityOverrideRepository.delete(override);
    }

    private void ensureUserTimezone(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
        validationService.validateTimezone(user.getTimezone());
    }
}
