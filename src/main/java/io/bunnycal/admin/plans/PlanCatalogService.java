package io.bunnycal.admin.plans;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.plans.dto.PlanDto;
import io.bunnycal.admin.plans.dto.PlanRequests.CreatePlanRequest;
import io.bunnycal.admin.plans.dto.PlanRequests.UpdatePlanRequest;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.BillingInterval;
import io.bunnycal.billing.domain.PlanVisibility;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.repository.SubscriptionPlanRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-side management of the billing catalog ({@code subscription_plans}). Replaces the
 * manual "edit product/price ids in the database" workflow with audited CRUD. Deliberately
 * separate from {@link io.bunnycal.billing.service.PlanService} (the customer-facing read
 * path used at checkout) so admin writes never entangle purchase logic. Every mutation is
 * recorded to {@code admin_audit_logs} with before/after snapshots.
 */
@Service
public class PlanCatalogService {

    private static final String TARGET_TYPE = "PLAN";

    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AdminAuditService auditService;
    private final UserRepository userRepository;

    public PlanCatalogService(SubscriptionPlanRepository planRepository,
                              SubscriptionRepository subscriptionRepository,
                              AdminAuditService auditService,
                              UserRepository userRepository) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PlanDto> list() {
        return planRepository.findAll(org.springframework.data.domain.Sort.by("sortOrder").ascending())
                .stream()
                .map(PlanDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanDto get(UUID id) {
        return PlanDto.from(require(id));
    }

    @Transactional
    public PlanDto create(UUID adminId, CreatePlanRequest req) {
        String code = requireText(req.code(), "code").toLowerCase(Locale.ROOT);
        planRepository.findByCode(code).ifPresent(existing -> {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "A plan with code '" + code + "' already exists.");
        });

        SubscriptionPlan plan = SubscriptionPlan.builder()
                .code(code)
                .name(requireText(req.name(), "name"))
                .description(req.description())
                .amountMinor(requireNonNegative(req.amountMinor(), "amountMinor"))
                .currency(requireCurrency(req.currency()))
                .billingInterval(req.billingInterval() == null ? BillingInterval.MONTH : req.billingInterval())
                .trialDays(req.trialDays() == null ? 0 : requireNonNegativeInt(req.trialDays(), "trialDays"))
                .providerProductId(blankToNull(req.providerProductId()))
                .providerPriceId(blankToNull(req.providerPriceId()))
                .visibility(req.visibility() == null ? PlanVisibility.PUBLIC : req.visibility())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();

        SubscriptionPlan saved = planRepository.save(plan);
        audit(adminId, "PLAN_CREATE", saved.getId(), null, null, PlanDto.from(saved));
        return PlanDto.from(saved);
    }

    @Transactional
    public PlanDto update(UUID adminId, UUID id, UpdatePlanRequest req) {
        SubscriptionPlan plan = require(id);
        PlanDto before = PlanDto.from(plan);

        if (req.name() != null) plan.setName(requireText(req.name(), "name"));
        if (req.description() != null) plan.setDescription(req.description());
        if (req.amountMinor() != null) plan.setAmountMinor(requireNonNegative(req.amountMinor(), "amountMinor"));
        if (req.currency() != null) plan.setCurrency(requireCurrency(req.currency()));
        if (req.billingInterval() != null) plan.setBillingInterval(req.billingInterval());
        if (req.trialDays() != null) plan.setTrialDays(requireNonNegativeInt(req.trialDays(), "trialDays"));
        if (req.providerProductId() != null) plan.setProviderProductId(blankToNull(req.providerProductId()));
        if (req.providerPriceId() != null) plan.setProviderPriceId(blankToNull(req.providerPriceId()));
        if (req.visibility() != null) plan.setVisibility(req.visibility());
        if (req.sortOrder() != null) plan.setSortOrder(req.sortOrder());
        if (plan.isDefaultPlan()) {
            requireDefaultConfiguration(plan);
        }

        SubscriptionPlan saved = planRepository.save(plan);
        audit(adminId, "PLAN_UPDATE", saved.getId(), null, before, PlanDto.from(saved));
        return PlanDto.from(saved);
    }

    @Transactional
    public PlanDto setActive(UUID adminId, UUID id, boolean active, String reason) {
        SubscriptionPlan plan = require(id);
        if (!active && plan.isDefaultPlan()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The default plan can't be deactivated. Make another plan the default first.");
        }
        PlanDto before = PlanDto.from(plan);
        plan.setActive(active);
        SubscriptionPlan saved = planRepository.save(plan);
        audit(adminId, active ? "PLAN_ACTIVATE" : "PLAN_DEACTIVATE", saved.getId(), reason, before, PlanDto.from(saved));
        return PlanDto.from(saved);
    }

    /**
     * Atomically moves the catalog default. The replacement must already be sellable and linked
     * to a provider price so checkout can never observe a half-configured default.
     */
    @Transactional
    public PlanDto setDefault(UUID adminId, UUID id, String reason) {
        SubscriptionPlan target = require(id);
        if (target.isDefaultPlan()) {
            return PlanDto.from(target);
        }
        if (!target.isActive()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Activate this plan before making it the default.");
        }
        requireDefaultConfiguration(target);

        PlanDto targetBefore = PlanDto.from(target);
        planRepository.findByDefaultPlanTrue().ifPresent(current -> {
            PlanDto currentBefore = PlanDto.from(current);
            current.setDefaultPlan(false);
            SubscriptionPlan savedCurrent = planRepository.saveAndFlush(current);
            audit(adminId, "PLAN_UNSET_DEFAULT", current.getId(), reason,
                    currentBefore, PlanDto.from(savedCurrent));
        });

        target.setDefaultPlan(true);
        SubscriptionPlan saved = planRepository.save(target);
        audit(adminId, "PLAN_SET_DEFAULT", saved.getId(), reason, targetBefore, PlanDto.from(saved));
        return PlanDto.from(saved);
    }

    @Transactional
    public PlanDto setVisibility(UUID adminId, UUID id, PlanVisibility visibility, String reason) {
        if (visibility == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "visibility is required.");
        }
        SubscriptionPlan plan = require(id);
        if (plan.isDefaultPlan() && visibility != PlanVisibility.PUBLIC) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The default plan must stay public. Make another plan the default first.");
        }
        PlanDto before = PlanDto.from(plan);
        plan.setVisibility(visibility);
        SubscriptionPlan saved = planRepository.save(plan);
        audit(adminId, "PLAN_SET_VISIBILITY", saved.getId(), reason, before, PlanDto.from(saved));
        return PlanDto.from(saved);
    }

    /**
     * Hard-delete a plan from the catalog. Guarded so a delete can never break billing:
     * the customer-facing default plan is protected, and any plan referenced by an existing
     * subscription is refused (deactivate/hide it instead). Recorded to the audit log with a
     * final before-snapshot.
     */
    @Transactional
    public void delete(UUID adminId, UUID id, String reason) {
        SubscriptionPlan plan = require(id);
        if (plan.isDefaultPlan()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The default plan can't be deleted. Make another plan the default first.");
        }
        if (subscriptionRepository.existsByPlanId(id)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "This plan is used by existing subscriptions and can't be deleted. Deactivate it instead.");
        }
        PlanDto before = PlanDto.from(plan);
        planRepository.delete(plan);
        audit(adminId, "PLAN_DELETE", id, reason, before, null);
    }

    private static void requireDefaultConfiguration(SubscriptionPlan plan) {
        if (plan.getVisibility() != PlanVisibility.PUBLIC) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The default plan must be public.");
        }
        if (plan.getProviderPriceId() == null || plan.getProviderPriceId().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Link a Dodo Price ID before making this plan the default.");
        }
    }

    private SubscriptionPlan require(UUID id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Plan not found."));
    }

    private void audit(UUID adminId, String action, UUID planId, String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(u -> u.getEmail()).orElse(null);
        auditService.record(adminId, email, action, TARGET_TYPE, planId, reason, before, after);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, field + " is required.");
        }
        return value.trim();
    }

    private static String requireCurrency(String value) {
        String currency = requireText(value, "currency").toUpperCase(Locale.ROOT);
        if (currency.length() != 3) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "currency must be a 3-letter ISO code.");
        }
        return currency;
    }

    private static long requireNonNegative(Long value, String field) {
        if (value == null || value < 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, field + " must be >= 0.");
        }
        return value;
    }

    private static int requireNonNegativeInt(Integer value, String field) {
        if (value == null || value < 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, field + " must be >= 0.");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
