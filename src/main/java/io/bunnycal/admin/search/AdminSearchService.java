package io.bunnycal.admin.search;

import io.bunnycal.admin.search.dto.AdminSearchDtos.AdminSearchResponse;
import io.bunnycal.admin.search.dto.AdminSearchDtos.SearchResultDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository.InvoiceSearchRow;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.repository.SubscriptionRepository.SubscriptionSearchRow;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.repository.BookingRepository.BookingSearchRow;
import io.bunnycal.payments.webhook.WebhookEventRepository;
import io.bunnycal.payments.webhook.WebhookEventRepository.WebhookSearchRow;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSearchService {

    private static final int LIMIT_PER_GROUP = 5;

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final BookingRepository bookingRepository;
    private final WebhookEventRepository webhookRepository;

    public AdminSearchService(UserRepository userRepository,
                              SubscriptionRepository subscriptionRepository,
                              SubscriptionInvoiceRepository invoiceRepository,
                              BookingRepository bookingRepository,
                              WebhookEventRepository webhookRepository) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.bookingRepository = bookingRepository;
        this.webhookRepository = webhookRepository;
    }

    @Transactional(readOnly = true)
    public AdminSearchResponse search(String query) {
        if (query == null || query.isBlank()) {
            return empty();
        }
        String q = query.trim();
        if (q.length() < 2) {
            return empty();
        }
        String pattern = "%" + q.toLowerCase(Locale.ROOT) + "%";

        List<SearchResultDto> users = Stream.concat(
                        exactUser(q).stream(),
                        userRepository.findTop20ByEmailContainingIgnoreCaseOrderByEmailAsc(q).stream())
                .distinct()
                .limit(LIMIT_PER_GROUP)
                .map(this::userResult)
                .toList();
        List<SearchResultDto> subscriptions = subscriptionRepository.searchAdmin(q, pattern, LIMIT_PER_GROUP).stream()
                .map(this::subscriptionResult)
                .toList();
        List<SearchResultDto> invoices = invoiceRepository.searchAdmin(q, pattern, LIMIT_PER_GROUP).stream()
                .map(this::invoiceResult)
                .toList();
        List<SearchResultDto> bookings = bookingRepository.searchAdmin(q, pattern, LIMIT_PER_GROUP).stream()
                .map(this::bookingResult)
                .toList();
        List<SearchResultDto> webhooks = webhookRepository.searchAdmin(q, pattern, LIMIT_PER_GROUP).stream()
                .map(this::webhookResult)
                .toList();

        return new AdminSearchResponse(users, subscriptions, invoices, bookings, webhooks);
    }

    private SearchResultDto userResult(User user) {
        return new SearchResultDto(
                "USER",
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getStatus().name(),
                "/users/" + user.getId(),
                user.getCreatedAt());
    }

    private List<User> exactUser(String query) {
        try {
            UUID id = UUID.fromString(query);
            return userRepository.findById(id).map(List::of).orElseGet(List::of);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private SearchResultDto subscriptionResult(SubscriptionSearchRow row) {
        return new SearchResultDto(
                "SUBSCRIPTION",
                row.getId(),
                row.getProviderSubscriptionId() == null ? row.getId().toString() : row.getProviderSubscriptionId(),
                "User " + row.getUserId(),
                row.getStatus(),
                "/users/" + row.getUserId(),
                row.getCreatedAt());
    }

    private SearchResultDto invoiceResult(InvoiceSearchRow row) {
        String total = row.getTotalMinor() == null ? "" : " · " + row.getCurrency() + " " + row.getTotalMinor();
        return new SearchResultDto(
                "INVOICE",
                row.getId(),
                row.getInvoiceNumber(),
                "User " + row.getUserId() + total,
                row.getStatus(),
                "/users/" + row.getUserId(),
                row.getIssuedAt());
    }

    private SearchResultDto bookingResult(BookingSearchRow row) {
        String guest = row.getGuestEmail() == null ? row.getGuestName() : row.getGuestEmail();
        return new SearchResultDto(
                "BOOKING",
                row.getId(),
                row.getEventTypeName() == null ? row.getId().toString() : row.getEventTypeName(),
                guest == null ? "Host " + row.getHostId() : guest,
                row.getStatus(),
                "/users/" + row.getHostId(),
                row.getStartTime());
    }

    private SearchResultDto webhookResult(WebhookSearchRow row) {
        return new SearchResultDto(
                "WEBHOOK",
                row.getId(),
                row.getType(),
                row.getProvider() + " · " + row.getProviderEventId(),
                row.getStatus(),
                "/webhooks",
                row.getReceivedAt());
    }

    private static AdminSearchResponse empty() {
        return new AdminSearchResponse(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
