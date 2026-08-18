package io.bunnycal.admin.users;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.subscriptions.AdminSubscriptionService;
import io.bunnycal.admin.subscriptions.dto.AdminSubscriptionDto;
import io.bunnycal.admin.users.dto.AdminUserActivityDto;
import io.bunnycal.admin.users.dto.AdminUserCalendarConnectionDto;
import io.bunnycal.admin.users.dto.AdminUserDetailDto;
import io.bunnycal.admin.users.dto.AdminUserEventTypeDto;
import io.bunnycal.admin.users.dto.AdminUserSummaryDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final SubscriptionStateService stateService;
    private final EntitlementService entitlementService;
    private final AdminSubscriptionService adminSubscriptionService;
    private final AdminAuditService auditService;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final BookingRepository bookingRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarConnectionCalendarRepository calendarConnectionCalendarRepository;
    private final CalendarEventRepository calendarEventRepository;

    public AdminUserService(UserRepository userRepository,
                            SubscriptionRepository subscriptionRepository,
                            SubscriptionInvoiceRepository invoiceRepository,
                            SubscriptionStateService stateService,
                            EntitlementService entitlementService,
                            AdminSubscriptionService adminSubscriptionService,
                            AdminAuditService auditService,
                            EventTypeRepository eventTypeRepository,
                            AvailabilityRuleRepository availabilityRuleRepository,
                            BookingRepository bookingRepository,
                            CalendarConnectionRepository calendarConnectionRepository,
                            CalendarConnectionCalendarRepository calendarConnectionCalendarRepository,
                            CalendarEventRepository calendarEventRepository) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.stateService = stateService;
        this.entitlementService = entitlementService;
        this.adminSubscriptionService = adminSubscriptionService;
        this.auditService = auditService;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.bookingRepository = bookingRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.calendarConnectionCalendarRepository = calendarConnectionCalendarRepository;
        this.calendarEventRepository = calendarEventRepository;
    }

    /**
     * Lists users newest-first, optionally resolving a free-text query. The query is interpreted
     * as, in order: a user UUID, a subscription UUID, a provider (Dodo) customer id, then a
     * partial email. Exact-id matches still use the standard page envelope.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserSummaryDto> search(String query, int page, int size) {
        PageRequest pageable = page(page, size);
        if (query == null || query.isBlank()) {
            return PageResponse.of(userRepository.findAll(pageable), AdminUserSummaryDto::from);
        }
        String q = query.trim();

        // 1. user id
        UUID asUuid = tryUuid(q);
        if (asUuid != null) {
            Optional<User> byId = userRepository.findById(asUuid);
            if (byId.isPresent()) {
                return singleResult(byId.get(), pageable);
            }
            // 2. subscription id → owning user
            Optional<UUID> ownerId = subscriptionRepository.findById(asUuid).map(s -> s.getUserId());
            if (ownerId.isPresent()) {
                return userRepository.findById(ownerId.get())
                        .map(user -> singleResult(user, pageable))
                        .orElseGet(() -> emptyResult(pageable));
            }
        }

        // 3. provider (Dodo) customer id → owning user
        Optional<UUID> byCustomer = subscriptionRepository
                .findFirstByProviderCustomerIdOrderByCreatedAtDesc(q)
                .map(s -> s.getUserId());
        if (byCustomer.isPresent()) {
            return userRepository.findById(byCustomer.get())
                    .map(user -> singleResult(user, pageable))
                    .orElseGet(() -> emptyResult(pageable));
        }

        // 4. partial email
        return PageResponse.of(
                userRepository.findByEmailContainingIgnoreCase(q, pageable),
                AdminUserSummaryDto::from);
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

        return AdminUserDetailDto.of(
                user,
                subscriptions,
                invoices,
                entitlements,
                activity(user),
                eventTypes(userId),
                calendarConnections(userId));
    }

    // ── Product activity: onboarding funnel, booking pages, connected calendars ────────

    /** Funnel position plus the counts that say whether this user ever built anything. */
    private AdminUserActivityDto activity(User user) {
        UUID userId = user.getId();
        return AdminUserActivityDto.of(
                user,
                eventTypeRepository.countByUserIdAndDeletedAtIsNull(userId),
                eventTypeRepository.countByUserIdAndPublishedTrueAndDeletedAtIsNull(userId),
                calendarConnectionRepository.findByUserIdOrderByCreatedAtAsc(userId).size(),
                availabilityRuleRepository.countByUserId(userId),
                bookingRepository.countByHostId(userId));
    }

    /**
     * Every booking page the user has, soft-deleted ones included and flagged. Ordered by
     * name: {@code EventType} does not map the table's timestamp columns, so there is no
     * creation order to sort on.
     */
    private List<AdminUserEventTypeDto> eventTypes(UUID userId) {
        return eventTypeRepository.findByUserIdOrderByNameAsc(userId).stream()
                .map(AdminUserEventTypeDto::from)
                .toList();
    }

    /**
     * Connected accounts with their sub-calendars and live event counts. Sub-calendars and
     * counts are each fetched in a single batched query, so the cost does not grow with the
     * number of connections.
     */
    private List<AdminUserCalendarConnectionDto> calendarConnections(UUID userId) {
        List<CalendarConnection> connections =
                calendarConnectionRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (connections.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = connections.stream().map(CalendarConnection::getId).toList();

        Map<UUID, List<CalendarConnectionCalendar>> calendarsByConnection =
                calendarConnectionCalendarRepository
                        .findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(ids)
                        .stream()
                        .collect(Collectors.groupingBy(CalendarConnectionCalendar::getConnectionId));

        Map<UUID, Long> eventCounts = calendarEventRepository.countLiveByConnectionIds(ids).stream()
                .collect(Collectors.toMap(
                        CalendarEventRepository.ConnectionEventCount::getConnectionId,
                        CalendarEventRepository.ConnectionEventCount::getEventCount));

        return connections.stream()
                .map(c -> AdminUserCalendarConnectionDto.from(
                        c,
                        calendarsByConnection.getOrDefault(c.getId(), List.of()),
                        eventCounts.getOrDefault(c.getId(), 0L)))
                .toList();
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

    private static PageRequest page(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Sort newestFirst = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id"));
        return PageRequest.of(safePage, safeSize, newestFirst);
    }

    private static PageResponse<AdminUserSummaryDto> singleResult(User user, PageRequest page) {
        List<AdminUserSummaryDto> items = page.getPageNumber() == 0
                ? List.of(AdminUserSummaryDto.from(user))
                : List.of();
        return new PageResponse<>(items, page.getPageNumber(), page.getPageSize(), 1, 1);
    }

    private static PageResponse<AdminUserSummaryDto> emptyResult(PageRequest page) {
        return new PageResponse<>(List.of(), page.getPageNumber(), page.getPageSize(), 0, 0);
    }
}
