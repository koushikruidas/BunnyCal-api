package io.bunnycal.admin.analytics;

import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.CountriesReportDto;
import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.TopEventDto;
import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.AnalyticsSummaryDto;
import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.TimezoneBreakdownDto;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.time.TimeSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only product analytics backed by stored user + booking data. This module is explicit
 * about current limits:
 * <ul>
 *   <li>Conversion is booking conversion, not visitor/session conversion:
 *       successful bookings / bookings created in the window.</li>
 *   <li>Popular events are ranked by successful bookings in the window.</li>
 *   <li>Countries are unavailable because the product does not persist a canonical country field.</li>
 * </ul>
 */
@Service
public class ProductAnalyticsService {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int TOP_EVENTS_LIMIT = 8;
    private static final int TOP_TIMEZONES_LIMIT = 10;
    private static final String CONVERSION_DEFINITION =
            "Successful bookings / bookings created in the selected range.";

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final TimeSource timeSource;

    public ProductAnalyticsService(UserRepository userRepository,
                                   BookingRepository bookingRepository,
                                   TimeSource timeSource) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public AnalyticsSummaryDto summary(Instant from, Instant to) {
        Window window = resolveWindow(from, to);

        long newUsers = userRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(window.from(), window.to());
        long bookingsCreated = bookingRepository.countCreatedBetween(window.from(), window.to());
        long bookingsCancelled = bookingRepository.countCancelledBetween(window.from(), window.to());
        long successfulBookings = bookingRepository.countConvertedFromCreatedBetween(window.from(), window.to());
        double conversionRate = bookingsCreated == 0 ? 0.0 : (double) successfulBookings / (double) bookingsCreated;
        Double averageDuration = bookingRepository.averageSuccessfulDurationMinutesBetween(window.from(), window.to());
        double averageDurationMinutes = averageDuration == null ? 0.0 : averageDuration;

        return new AnalyticsSummaryDto(
                timeSource.now(),
                window.from(),
                window.to(),
                newUsers,
                bookingsCreated,
                bookingsCancelled,
                successfulBookings,
                conversionRate,
                averageDurationMinutes,
                CONVERSION_DEFINITION);
    }

    @Transactional(readOnly = true)
    public List<TopEventDto> topEvents(Instant from, Instant to) {
        Window window = resolveWindow(from, to);
        return bookingRepository.topEventsBetween(window.from(), window.to(), TOP_EVENTS_LIMIT).stream()
                .map(row -> new TopEventDto(
                        row.getEventTypeId(),
                        row.getEventName(),
                        row.getBookingCount() == null ? 0L : row.getBookingCount(),
                        Boolean.TRUE.equals(row.getPublished()),
                        Boolean.TRUE.equals(row.getActive())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimezoneBreakdownDto> timezones() {
        return userRepository.timezoneCounts().stream()
                .limit(TOP_TIMEZONES_LIMIT)
                .map(row -> new TimezoneBreakdownDto(
                        row.getTimezone(),
                        row.getUserCount() == null ? 0L : row.getUserCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CountriesReportDto countries() {
        return new CountriesReportDto(
                false,
                "Country is not currently stored on users, bookings, or invoices.",
                List.of());
    }

    private Window resolveWindow(Instant from, Instant to) {
        Instant end = to != null ? to : timeSource.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);
        return new Window(start, end);
    }

    private record Window(Instant from, Instant to) {
    }
}
