package io.bunnycal.admin.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminAnalyticsDtos {

    private AdminAnalyticsDtos() {
    }

    public record AnalyticsSummaryDto(
            Instant generatedAt,
            Instant from,
            Instant to,
            long newUsers,
            long bookingsCreated,
            long bookingsCancelled,
            long successfulBookings,
            double bookingConversionRate,
            double averageSuccessfulBookingDurationMinutes,
            String conversionDefinition) {
    }

    public record TopEventDto(
            UUID eventTypeId,
            String eventName,
            long bookingCount,
            boolean published,
            boolean active) {
    }

    public record TimezoneBreakdownDto(
            String timezone,
            long userCount) {
    }

    public record CountriesReportDto(
            boolean available,
            String reason,
            List<CountryBreakdownDto> items) {
    }

    public record CountryBreakdownDto(
            String countryCode,
            long count) {
    }
}
