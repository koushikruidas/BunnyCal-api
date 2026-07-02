package io.bunnycal.admin.search.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminSearchDtos {

    private AdminSearchDtos() {
    }

    public record AdminSearchResponse(
            List<SearchResultDto> users,
            List<SearchResultDto> subscriptions,
            List<SearchResultDto> invoices,
            List<SearchResultDto> bookings,
            List<SearchResultDto> webhooks) {
    }

    public record SearchResultDto(
            String type,
            UUID id,
            String title,
            String subtitle,
            String status,
            String url,
            Instant timestamp) {
    }
}
