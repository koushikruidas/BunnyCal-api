package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.SessionUserResolver;
import io.bunnycal.availability.domain.AvailabilityOverride;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.dto.AvailabilityOverrideCreateRequest;
import io.bunnycal.availability.dto.AvailabilityOverrideResponse;
import io.bunnycal.availability.dto.AvailabilityRuleResponse;
import io.bunnycal.availability.dto.BulkAvailabilityRulesUpsertRequest;
import io.bunnycal.availability.dto.GroupReservationBlockerResponse;
import io.bunnycal.availability.mapper.AvailabilityOverrideMapper;
import io.bunnycal.availability.mapper.AvailabilityRuleMapper;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.availability.validation.AvailabilityValidationService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.service.ParticipantSetupRequestService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final AvailabilityRuleMapper availabilityRuleMapper;
    private final AvailabilityOverrideMapper availabilityOverrideMapper;
    private final AvailabilityValidationService validationService;
    private final UserRepository userRepository;
    private final SessionUserResolver sessionUserResolver;
    private final ParticipantSetupRequestService setupRequestService;
    private final ParticipantEligibilityService eligibilityService;

    public AvailabilityService(
            AvailabilityRuleRepository availabilityRuleRepository,
            AvailabilityOverrideRepository availabilityOverrideRepository,
            GroupEventReservationWindowRepository reservationWindowRepository,
            AvailabilityRuleMapper availabilityRuleMapper,
            AvailabilityOverrideMapper availabilityOverrideMapper,
            AvailabilityValidationService validationService,
            UserRepository userRepository,
            SessionUserResolver sessionUserResolver,
            ParticipantSetupRequestService setupRequestService,
            ParticipantEligibilityService eligibilityService) {
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.reservationWindowRepository = reservationWindowRepository;
        this.availabilityRuleMapper = availabilityRuleMapper;
        this.availabilityOverrideMapper = availabilityOverrideMapper;
        this.validationService = validationService;
        this.userRepository = userRepository;
        this.sessionUserResolver = sessionUserResolver;
        this.setupRequestService = setupRequestService;
        this.eligibilityService = eligibilityService;
    }

    @Transactional(readOnly = true)
    public List<AvailabilityRuleResponse> getRules(UUID userId) {
        ensureUserTimezone(userId);
        return availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).stream()
                .map(availabilityRuleMapper::toResponse)
                .toList();
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

        List<AvailabilityRuleResponse> saved = availabilityRuleRepository.saveAll(toSave).stream()
                .map(availabilityRuleMapper::toResponse)
                .toList();
        if (eligibilityService.isReady(userId)) {
            setupRequestService.markAllCompletedForTarget(userId);
        }
        return saved;
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

    @Transactional(readOnly = true)
    public List<GroupReservationBlockerResponse> getReservationBlockers(UUID userId) {
        sessionUserResolver.require(userId, "GET:/api/availability/reservation-blockers");
        return reservationWindowRepository.findAllWindowsWithEventNameByHost(userId);
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
        User user = sessionUserResolver.require(userId, "availability-endpoint");
        validationService.validateTimezone(user.getTimezone());
    }
}
