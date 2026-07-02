package io.bunnycal.admin.users;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.subscriptions.AdminSubscriptionService;
import io.bunnycal.admin.subscriptions.dto.AdminSubscriptionDto;
import io.bunnycal.admin.users.dto.AdminUserDetailDto;
import io.bunnycal.admin.users.dto.AdminUserSummaryDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.dto.InvoiceDto;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.EntitlementsDto;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin user management: search, detail aggregation, status changes, and plan grants.
 * Orchestrates existing repositories/services (UserRepository, SubscriptionRepository,
 * SubscriptionInvoiceRepository, EntitlementService, {@link AdminSubscriptionService}); it
 * does not reimplement billing logic. Suspend/unsuspend use {@link UserStatus}; plan actions
 * delegate to {@link AdminSubscriptionService} so subscription state lives in one place.
 */
@Service
public class AdminUserService {

    private static final String TARGET_TYPE = "USER";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final SubscriptionStateService stateService;
    private final EntitlementService entitlementService;
    private final AdminSubscriptionService adminSubscriptionService;
    private final AdminAuditService auditService;

    public AdminUserService(UserRepository userRepository,
                            SubscriptionRepository subscriptionRepository,
                            SubscriptionInvoiceRepository invoiceRepository,
                            SubscriptionStateService stateService,
                            EntitlementService entitlementService,
                            AdminSubscriptionService adminSubscriptionService,
                            AdminAuditService auditService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.stateService = stateService;
        this.entitlementService = entitlementService;
        this.adminSubscriptionService = adminSubscriptionService;
        this.auditService = auditService;
    }

    /**
     * Resolves a free-text query to matching users. The query is interpreted as, in order:
     * a user UUID, a subscription UUID, a provider (Dodo) customer id, then a partial email.
     * Returns at most a small page; exact-id matches return a single user.
     */
    @Transactional(readOnly = true)
    public List<AdminUserSummaryDto> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();

        // 1. user id
        UUID asUuid = tryUuid(q);
        if (asUuid != null) {
            Optional<User> byId = userRepository.findById(asUuid);
            if (byId.isPresent()) {
                return List.of(AdminUserSummaryDto.from(byId.get()));
            }
            // 2. subscription id → owning user
            Optional<UUID> ownerId = subscriptionRepository.findById(asUuid).map(s -> s.getUserId());
            if (ownerId.isPresent()) {
                return userRepository.findById(ownerId.get())
                        .map(AdminUserSummaryDto::from)
                        .map(List::of)
                        .orElseGet(List::of);
            }
        }

        // 3. provider (Dodo) customer id → owning user
        Optional<UUID> byCustomer = subscriptionRepository
                .findFirstByProviderCustomerIdOrderByCreatedAtDesc(q)
                .map(s -> s.getUserId());
        if (byCustomer.isPresent()) {
            return userRepository.findById(byCustomer.get())
                    .map(AdminUserSummaryDto::from)
                    .map(List::of)
                    .orElseGet(List::of);
        }

        // 4. partial email
        return userRepository.findTop20ByEmailContainingIgnoreCaseOrderByEmailAsc(q).stream()
                .map(AdminUserSummaryDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDto detail(UUID userId) {
        User user = requireUser(userId);

        List<AdminSubscriptionDto> subscriptions = subscriptionRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> AdminSubscriptionDto.from(s, stateService.isEntitled(s)))
                .toList();

        List<InvoiceDto> invoices = invoiceRepository.findByUserIdOrderByIssuedAtDesc(userId).stream()
                .map(InvoiceDto::from)
                .toList();

        EntitlementsDto entitlements = EntitlementsDto.from(entitlementService.resolve(userId));

        return AdminUserDetailDto.of(user, subscriptions, invoices, entitlements);
    }

    @Transactional
    public AdminUserDetailDto suspend(UUID adminId, UUID userId, String reason) {
        return setStatus(adminId, userId, UserStatus.INACTIVE, "USER_SUSPEND", reason);
    }

    @Transactional
    public AdminUserDetailDto unsuspend(UUID adminId, UUID userId, String reason) {
        return setStatus(adminId, userId, UserStatus.ACTIVE, "USER_UNSUSPEND", reason);
    }

    private AdminUserDetailDto setStatus(UUID adminId, UUID userId, UserStatus status,
                                         String action, String reason) {
        User user = requireUser(userId);
        UserStatus before = user.getStatus();
        if (before == UserStatus.DELETED) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot change status of a deleted account.");
        }
        user.setStatus(status);
        userRepository.save(user);
        audit(adminId, action, userId, reason,
                java.util.Map.of("status", before.name()),
                java.util.Map.of("status", status.name()));
        return detail(userId);
    }

    // ── Plan actions — delegate subscription state to AdminSubscriptionService ──────────

    @Transactional
    public AdminUserDetailDto grantPro(UUID adminId, UUID userId, String reason) {
        requireUser(userId);
        adminSubscriptionService.grantPro(adminId, userId, reason);
        return detail(userId);
    }

    @Transactional
    public AdminUserDetailDto grantTrial(UUID adminId, UUID userId, Integer days, String reason) {
        requireUser(userId);
        adminSubscriptionService.grantTrial(adminId, userId, days, reason);
        return detail(userId);
    }

    @Transactional
    public AdminUserDetailDto setFree(UUID adminId, UUID userId, String reason) {
        requireUser(userId);
        adminSubscriptionService.setFree(adminId, userId, reason);
        return detail(userId);
    }

    @Transactional
    public AdminUserDetailDto removePro(UUID adminId, UUID userId, String reason) {
        requireUser(userId);
        adminSubscriptionService.setFree(adminId, userId, reason);
        return detail(userId);
    }

    @Transactional
    public AdminUserDetailDto grantLifetime(UUID adminId, UUID userId, String reason) {
        requireUser(userId);
        adminSubscriptionService.grantLifetime(adminId, userId, reason);
        return detail(userId);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private void audit(UUID adminId, String action, UUID userId, String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(User::getEmail).orElse(null);
        auditService.record(adminId, email, action, TARGET_TYPE, userId, reason, before, after);
    }

    private static UUID tryUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
