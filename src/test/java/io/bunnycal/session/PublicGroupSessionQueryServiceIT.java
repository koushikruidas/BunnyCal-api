package io.bunnycal.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.dto.PublicGroupDateCardResponse;
import io.bunnycal.booking.dto.PublicGroupDateStatus;
import io.bunnycal.booking.dto.PublicGroupSessionCardResponse;
import io.bunnycal.booking.dto.PublicGroupSessionsResponse;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.booking.service.PublicGroupSessionQueryService;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PublicGroupSessionQueryServiceIT extends AbstractSessionIT {

    @Autowired private PublicGroupSessionQueryService publicGroupSessionQueryService;
    @Autowired private PublicBookingService publicBookingService;

    private static final LocalDate TEST_DATE = nextMonday(7);

    private static LocalDate nextMonday(int minDaysFromNow) {
        LocalDate base = LocalDate.now().plusDays(minDaysFromNow);
        int daysUntilMonday = (DayOfWeek.MONDAY.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(daysUntilMonday);
    }

    @BeforeEach
    void cleanExtra() {
        jdbc.execute("TRUNCATE TABLE bookings, availability_rules CASCADE");
    }

    @Test
    void returnsDerivedFutureSessionsWithoutPersistedRows() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 10, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        assertThat(response.timezone()).isEqualTo("UTC");
        assertThat(response.seriesSummary()).isNotNull();
        assertThat(response.seriesSummary().label()).isEqualTo("Recurring series");
        assertThat(response.seriesSummary().scheduleText()).contains("Every Monday").contains("9:00–11:00 AM");
        assertThat(response.seriesSummary().sessionCountPerOccurrence()).isEqualTo(2);

        PublicGroupDateCardResponse date = response.dates().get(0);
        assertThat(date.date()).isEqualTo(TEST_DATE);
        assertThat(date.status()).isEqualTo(PublicGroupDateStatus.OPEN);
        assertThat(date.sessionCount()).isEqualTo(2);
        assertThat(date.bookableSessionCount()).isEqualTo(2);
        assertThat(date.totalCapacity()).isEqualTo(20);
        assertThat(date.totalBooked()).isZero();
        assertThat(date.nextAvailableStartTime()).hasToString("09:00");

        PublicGroupSessionCardResponse firstSession = date.sessions().get(0);
        assertThat(firstSession.bookable()).isTrue();
        assertThat(firstSession.bookedCount()).isZero();
        assertThat(firstSession.spotsLeft()).isEqualTo(10);
        assertThat(firstSession.occupancyPercent()).isZero();
        assertThat(firstSession.attendeePreview()).isEmpty();
        assertThat(firstSession.sessionId()).startsWith("derived:");
    }

    @Test
    void returnsFillingUpStatusAndAttendeePreviewFromPersistedSessionData() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 10, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        Instant firstSessionStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 7; i++) {
            var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                    new io.bunnycal.booking.dto.PublicBookRequest(
                            firstSessionStart,
                            "guest" + i + "@test.com",
                            "Guest " + i));
            publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());
        }

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        PublicGroupDateCardResponse date = response.dates().get(0);
        assertThat(date.status()).isEqualTo(PublicGroupDateStatus.FILLING_UP);
        assertThat(date.totalBooked()).isEqualTo(7);

        PublicGroupSessionCardResponse firstSession = date.sessions().get(0);
        assertThat(firstSession.sessionId()).doesNotStartWith("derived:");
        assertThat(firstSession.bookedCount()).isEqualTo(7);
        assertThat(firstSession.spotsLeft()).isEqualTo(3);
        assertThat(firstSession.occupancyPercent()).isEqualTo(70);
        assertThat(firstSession.bookable()).isTrue();
        assertThat(firstSession.attendeePreview()).hasSize(3);
        assertThat(firstSession.additionalAttendeeCount()).isEqualTo(4);
        assertThat(firstSession.attendeePreview().get(0).displayName()).isEqualTo("Guest 0");
        assertThat(firstSession.attendeePreview().get(0).initials()).isEqualTo("G0");
    }

    @Test
    void hidesSessionsWhenAdvanceWindowBlocksBooking() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 5, Duration.ZERO, Duration.ofDays(1));
        insertRecurringWindow(eventType.getId(), "09:00", "10:00");

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        PublicGroupDateCardResponse date = response.dates().get(0);
        assertThat(date.status()).isEqualTo(PublicGroupDateStatus.NO_SESSIONS);
        assertThat(date.sessionCount()).isZero();
        assertThat(date.bookableSessionCount()).isZero();
        assertThat(date.totalBooked()).isZero();
        assertThat(date.sessions()).isEmpty();
    }

    @Test
    void keepsSoldOutSessionsVisibleWhenNoSeatsRemain() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 1, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "10:00");

        Instant firstSessionStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        firstSessionStart,
                        "guest@test.com",
                        "Guest Zero"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        PublicGroupDateCardResponse date = response.dates().get(0);
        assertThat(date.status()).isEqualTo(PublicGroupDateStatus.FULLY_BOOKED);
        assertThat(date.sessionCount()).isEqualTo(1);
        assertThat(date.bookableSessionCount()).isZero();
        assertThat(date.sessions()).hasSize(1);
        assertThat(date.sessions().get(0).bookable()).isFalse();
        assertThat(date.sessions().get(0).spotsLeft()).isZero();
    }

    private User createHostWithAvailability() {
        User host = createHost();
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '09:00', '17:00', NOW(), NOW())
                """, UUID.randomUUID(), host.getId());
        return host;
    }

    private EventType createHourlyGroupType(UUID hostId, int capacity, Duration minNotice, Duration maxAdvance) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name("Group Workshop")
                .slug("group-workshop-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofHours(1))
                .minNotice(minNotice)
                .maxAdvance(maxAdvance)
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.GROUP)
                .capacity(capacity)
                .published(true)
                .build());
    }

    private void insertRecurringWindow(UUID eventTypeId, String startTime, String endTime) {
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode,
                     created_at, updated_at)
                VALUES (?, ?, 'MONDAY', ?::time, ?::time,
                        'RECURRING', 'WEEKLY', '2000-01-01', 'NONE', NOW(), NOW())
                """, UUID.randomUUID(), eventTypeId, startTime, endTime);
    }
}
