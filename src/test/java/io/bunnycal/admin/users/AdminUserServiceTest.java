package io.bunnycal.admin.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.subscriptions.AdminSubscriptionService;
import io.bunnycal.admin.users.dto.AdminUserSummaryDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.enums.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionInvoiceRepository invoiceRepository;
    @Mock private SubscriptionStateService stateService;
    @Mock private EntitlementService entitlementService;
    @Mock private AdminSubscriptionService adminSubscriptionService;
    @Mock private AdminAuditService auditService;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(
                userRepository,
                subscriptionRepository,
                invoiceRepository,
                stateService,
                entitlementService,
                adminSubscriptionService,
                auditService);
    }

    @Test
    void search_withoutQueryReturnsPaginatedUsersNewestFirst() {
        User user = user("new@example.com", Instant.parse("2026-07-24T08:00:00Z"));
        when(userRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), Pageable.ofSize(10), 21));

        PageResponse<AdminUserSummaryDto> result = service.search(null, 0, 10);

        assertEquals(1, result.items().size());
        assertEquals(21, result.totalElements());
        assertEquals(3, result.totalPages());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(pageable.capture());
        assertNewestFirst(pageable.getValue());
    }

    @Test
    void search_byEmailIsTrimmedPaginatedAndNewestFirst() {
        User user = user("alice@example.com", Instant.parse("2026-07-23T08:00:00Z"));
        when(subscriptionRepository.findFirstByProviderCustomerIdOrderByCreatedAtDesc("alice"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailContainingIgnoreCase(
                org.mockito.ArgumentMatchers.eq("alice"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), Pageable.ofSize(10), 1));

        PageResponse<AdminUserSummaryDto> result = service.search("  alice  ", 0, 10);

        assertEquals("alice@example.com", result.items().get(0).email());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findByEmailContainingIgnoreCase(
                org.mockito.ArgumentMatchers.eq("alice"), pageable.capture());
        assertNewestFirst(pageable.getValue());
    }

    private static void assertNewestFirst(Pageable pageable) {
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("createdAt").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }

    private static User user(String email, Instant createdAt) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .name("Test User")
                .timezone("UTC")
                .status(UserStatus.ACTIVE)
                .build();
        user.setCreatedAt(createdAt);
        return user;
    }
}
