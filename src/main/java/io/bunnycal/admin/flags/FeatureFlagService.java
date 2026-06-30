package io.bunnycal.admin.flags;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.AdminFeatureFlagDto;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.DeleteFeatureFlagOverrideRequest;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.FeatureFlagOverrideDto;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.UpdateFeatureFlagRequest;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.UpsertFeatureFlagOverrideRequest;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.entitlement.Entitlements;
import io.bunnycal.billing.entitlement.Feature;
import io.bunnycal.billing.entitlement.PlanCatalog;
import io.bunnycal.billing.entitlement.PlanTier;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-backed feature flags layered over plan entitlements.
 *
 * <p>Precedence:
 * 1. disabled flag definition => false (master kill switch)
 * 2. per-user override
 * 3. global override
 * 4. flag default_value (when true)
 * 5. PlanCatalog fallback
 */
@Service
public class FeatureFlagService {

    private static final String TARGET_TYPE = "FLAG";

    private final FeatureFlagRepository flagRepository;
    private final FeatureFlagOverrideRepository overrideRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final SubscriptionStateService subscriptionStateService;

    public FeatureFlagService(FeatureFlagRepository flagRepository,
                              FeatureFlagOverrideRepository overrideRepository,
                              UserRepository userRepository,
                              AdminAuditService auditService,
                              SubscriptionStateService subscriptionStateService) {
        this.flagRepository = flagRepository;
        this.overrideRepository = overrideRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.subscriptionStateService = subscriptionStateService;
    }

    @Transactional(readOnly = true)
    public Entitlements applyOverrides(UUID userId, Entitlements base) {
        ensureDefinitions();
        Map<String, FeatureFlagEntity> flags = flagsByKey();
        Map<String, FeatureFlagOverrideEntity> globals = globalOverridesByKey();
        Map<String, FeatureFlagOverrideEntity> userOverrides = userOverridesByKey(userId);

        Set<Feature> effective = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (resolveValue(feature, base.has(feature), flags, globals, userOverrides)) {
                effective.add(feature);
            }
        }
        return new Entitlements(base.tier(), Set.copyOf(effective), base.limits());
    }

    @Transactional(readOnly = true)
    public List<AdminFeatureFlagDto> list(UUID userId) {
        ensureDefinitions();
        Map<String, FeatureFlagOverrideEntity> globals = globalOverridesByKey();
        Map<String, FeatureFlagOverrideEntity> userOverrides = userId == null ? Map.of() : userOverridesByKey(userId);
        PlanTier tier = userId == null ? PlanTier.FREE : planTierForUser(userId);
        Entitlements base = PlanCatalog.forTier(tier);

        List<AdminFeatureFlagDto> result = new ArrayList<>();
        for (FeatureFlagEntity flag : flagRepository.findAllByOrderByKeyAsc()) {
            Feature feature = Feature.valueOf(flag.getKey());
            FeatureFlagOverrideEntity global = globals.get(flag.getKey());
            FeatureFlagOverrideEntity user = userOverrides.get(flag.getKey());
            Boolean effective = userId == null ? null : resolveValue(feature, base.has(feature), Map.of(flag.getKey(), flag), globals, userOverrides);
            result.add(new AdminFeatureFlagDto(
                    flag.getKey(),
                    flag.getDescription(),
                    flag.isEnabled(),
                    flag.isDefaultValue(),
                    base.has(feature),
                    toDto(global),
                    toDto(user),
                    effective,
                    overrideRepository.countByFlagKey(flag.getKey())));
        }
        return result;
    }

    @Transactional
    public AdminFeatureFlagDto updateDefinition(UUID adminId, String key, UpdateFeatureFlagRequest request) {
        ensureDefinitions();
        FeatureFlagEntity flag = requireFlag(key);
        AdminFeatureFlagDto before = findForAudit(key, null);
        if (request.description() != null) {
            flag.setDescription(request.description().trim());
        }
        if (request.enabled() != null) {
            flag.setEnabled(request.enabled());
        }
        if (request.defaultValue() != null) {
            flag.setDefaultValue(request.defaultValue());
        }
        flagRepository.save(flag);
        AdminFeatureFlagDto after = findForAudit(key, null);
        audit(adminId, "FLAG_UPDATE", request.reason(), before, after);
        return after;
    }

    @Transactional
    public AdminFeatureFlagDto upsertOverride(UUID adminId, String key, UpsertFeatureFlagOverrideRequest request) {
        ensureDefinitions();
        requireReason(request.reason());
        if (request.value() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "value is required.");
        }
        requireFlag(key);
        if (request.userId() != null) {
            requireUser(request.userId());
        }
        AdminFeatureFlagDto before = findForAudit(key, request.userId());
        FeatureFlagOverrideEntity override = request.userId() == null
                ? overrideRepository.findByFlagKeyAndUserIdIsNull(key).orElseGet(FeatureFlagOverrideEntity::new)
                : overrideRepository.findByFlagKeyAndUserId(key, request.userId()).orElseGet(FeatureFlagOverrideEntity::new);
        if (override.getId() == null) {
            override.setId(UUID.randomUUID());
        }
        override.setFlagKey(key);
        override.setUserId(request.userId());
        override.setValue(request.value());
        override.setReason(request.reason());
        override.setCreatedBy(adminId);
        overrideRepository.save(override);
        AdminFeatureFlagDto after = findForAudit(key, request.userId());
        audit(adminId, request.userId() == null ? "FLAG_GLOBAL_OVERRIDE_SET" : "FLAG_USER_OVERRIDE_SET", request.reason(), before, after);
        return after;
    }

    @Transactional
    public AdminFeatureFlagDto deleteOverride(UUID adminId, String key, UUID overrideId, DeleteFeatureFlagOverrideRequest request) {
        requireReason(request.reason());
        FeatureFlagOverrideEntity override = overrideRepository.findById(overrideId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Feature flag override not found."));
        if (!override.getFlagKey().equals(key)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Override does not belong to this flag.");
        }
        AdminFeatureFlagDto before = findForAudit(key, override.getUserId());
        overrideRepository.delete(override);
        AdminFeatureFlagDto after = findForAudit(key, override.getUserId());
        audit(adminId, override.getUserId() == null ? "FLAG_GLOBAL_OVERRIDE_DELETE" : "FLAG_USER_OVERRIDE_DELETE",
                request.reason(), before, after);
        return after;
    }

    private boolean resolveValue(Feature feature,
                                 boolean planFallback,
                                 Map<String, FeatureFlagEntity> flags,
                                 Map<String, FeatureFlagOverrideEntity> globals,
                                 Map<String, FeatureFlagOverrideEntity> users) {
        FeatureFlagEntity flag = flags.get(feature.name());
        if (flag == null) {
            return planFallback;
        }
        if (!flag.isEnabled()) {
            return false;
        }
        FeatureFlagOverrideEntity user = users.get(feature.name());
        if (user != null) {
            return user.isValue();
        }
        FeatureFlagOverrideEntity global = globals.get(feature.name());
        if (global != null) {
            return global.isValue();
        }
        if (flag.isDefaultValue()) {
            return true;
        }
        return planFallback;
    }

    private void ensureDefinitions() {
        for (Feature feature : Feature.values()) {
            if (flagRepository.existsById(feature.name())) {
                continue;
            }
            flagRepository.save(FeatureFlagEntity.builder()
                    .key(feature.name())
                    .description(defaultDescription(feature))
                    .defaultValue(false)
                    .enabled(true)
                    .build());
        }
    }

    private Map<String, FeatureFlagEntity> flagsByKey() {
        Map<String, FeatureFlagEntity> map = new HashMap<>();
        for (FeatureFlagEntity flag : flagRepository.findAll()) {
            map.put(flag.getKey(), flag);
        }
        return map;
    }

    private Map<String, FeatureFlagOverrideEntity> globalOverridesByKey() {
        Map<String, FeatureFlagOverrideEntity> map = new HashMap<>();
        for (FeatureFlagOverrideEntity override : overrideRepository.findByUserIdIsNull()) {
            map.put(override.getFlagKey(), override);
        }
        return map;
    }

    private Map<String, FeatureFlagOverrideEntity> userOverridesByKey(UUID userId) {
        Map<String, FeatureFlagOverrideEntity> map = new HashMap<>();
        for (FeatureFlagOverrideEntity override : overrideRepository.findByUserId(userId)) {
            map.put(override.getFlagKey(), override);
        }
        return map;
    }

    private AdminFeatureFlagDto findForAudit(String key, UUID userId) {
        return list(userId).stream()
                .filter(dto -> dto.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Feature flag not found."));
    }

    private FeatureFlagEntity requireFlag(String key) {
        try {
            Feature.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Unknown feature flag key.");
        }
        return flagRepository.findById(key)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Feature flag not found."));
    }

    private UUID requireUser(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private PlanTier planTierForUser(UUID userId) {
        requireUser(userId);
        return subscriptionStateService.resolveTier(userId);
    }

    private void audit(UUID adminId, String action, String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(User::getEmail).orElse(null);
        auditService.record(adminId, email, action, TARGET_TYPE, null, reason, before, after);
    }

    private static FeatureFlagOverrideDto toDto(FeatureFlagOverrideEntity override) {
        if (override == null) {
            return null;
        }
        return new FeatureFlagOverrideDto(
                override.getId(),
                override.getUserId(),
                override.isValue(),
                override.getReason(),
                override.getCreatedBy(),
                override.getCreatedAt());
    }

    private static String defaultDescription(Feature feature) {
        return switch (feature) {
            case GROUP_EVENT -> "Group event type (one host, many invitees on one slot).";
            case ROUND_ROBIN_EVENT -> "Round-robin event type (assign among team members).";
            case COLLECTIVE_EVENT -> "Collective event type (joint availability of multiple hosts).";
            case TEAMS -> "Team creation and management.";
            case BOOKING_FORMS -> "Booking forms and questionnaires.";
            case EXPERIENCES -> "Booking experiences and composed public surfaces.";
        };
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "reason is required.");
        }
    }
}
