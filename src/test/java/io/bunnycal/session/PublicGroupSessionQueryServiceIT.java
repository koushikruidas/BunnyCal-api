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
    @Autowired private io.bunnycal.session.service.SessionService sessionService;

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

    /**
     * A host-rescheduled session must not leave its original time behind as a bookable
     * empty session. The recurrence rule keeps generating the vacated occurrence, so the
     * grid has to recognise that the session it produced now sits elsewhere — otherwise
     * guests see the old time offered with 0 bookings while the real session, holding
     * their peers' registrations, has moved.
     */
    @Test
    void hostRescheduledSession_doesNotLeaveVacatedSlotBookable() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 10, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        Instant originalStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        originalStart, "guest@test.com", "Guest Zero"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());

        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                UUID.class, eventType.getId(), java.sql.Timestamp.from(originalStart));

        // Move it to 10:00, which the same window also generates.
        Instant newStart = TEST_DATE.atTime(10, 0).toInstant(ZoneOffset.UTC);
        sessionService.rescheduleSession(sessionId, host.getId(), newStart);

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        PublicGroupDateCardResponse date = response.dates().get(0);
        assertThat(date.sessions())
                .as("the vacated 09:00 occurrence must not be re-offered")
                .noneMatch(session -> session.startTime().equals(originalStart));

        PublicGroupSessionCardResponse moved = date.sessions().stream()
                .filter(session -> session.startTime().equals(newStart))
                .findFirst()
                .orElseThrow();
        assertThat(moved.bookedCount()).isEqualTo(1);
        assertThat(moved.sessionId()).isEqualTo(sessionId.toString());
    }

    /**
     * Sessions materialized before lineage tracking have a null occurrence start. Rescheduling
     * one must seed it from the time being vacated, or the grid has no way to know the slot was
     * abandoned and re-offers it — the same defect as above, on the rows most likely to hit it.
     */
    @Test
    void legacySessionWithoutLineage_stillSuppressesVacatedSlotAfterReschedule() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 10, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        Instant originalStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        originalStart, "guest@test.com", "Guest Zero"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());

        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                UUID.class, eventType.getId(), java.sql.Timestamp.from(originalStart));

        // Reproduce a pre-V131 row: lineage was never recorded.
        jdbc.update("UPDATE event_sessions SET scheduled_occurrence_start = NULL,"
                + " reservation_window_id = NULL WHERE id = ?", sessionId);

        Instant newStart = TEST_DATE.atTime(10, 0).toInstant(ZoneOffset.UTC);
        sessionService.rescheduleSession(sessionId, host.getId(), newStart);

        assertThat(jdbc.queryForObject(
                "SELECT scheduled_occurrence_start FROM event_sessions WHERE id = ?",
                java.sql.Timestamp.class, sessionId))
                .as("the vacated time must be recorded when the session detaches")
                .isEqualTo(java.sql.Timestamp.from(originalStart));

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        assertThat(response.dates().get(0).sessions())
                .noneMatch(session -> session.startTime().equals(originalStart));
    }

    /**
     * A session moved to a time no window generates still has registrants, so it must remain
     * visible on the public page — just not bookable, since the host never opened that time for
     * booking. Deriving cards from the rule alone would erase a meeting people are attending.
     */
    @Test
    void fullSessionMovedOffTheRuleGrid_staysVisibleButNotBookable() {
        User host = createHostWithAvailability();
        // Capacity 1, so the single booking fills it: seats, not the grid, are what close a
        // moved session. A session with seats left stays bookable at its new time — covered by
        // movedSessionWithSeatsLeft_isBookableAtItsNewTime.
        EventType eventType = createHourlyGroupType(host.getId(), 1, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        Instant originalStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        originalStart, "guest@test.com", "Guest Zero"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());

        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                UUID.class, eventType.getId(), java.sql.Timestamp.from(originalStart));

        // 14:00 is inside working hours but outside the 09:00-11:00 reservation window,
        // so the rule generates nothing there.
        Instant offGridStart = TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC);
        sessionService.rescheduleSession(sessionId, host.getId(), offGridStart);

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        PublicGroupDateCardResponse date = response.dates().get(0);
        PublicGroupSessionCardResponse moved = date.sessions().stream()
                .filter(session -> session.startTime().equals(offGridStart))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "off-grid session vanished from the public page: " + date.sessions()));

        assertThat(moved.sessionId()).isEqualTo(sessionId.toString());
        assertThat(moved.bookedCount()).isEqualTo(1);
        assertThat(moved.spotsLeft()).isZero();
        assertThat(moved.bookable())
                .as("sold out, so not bookable — but still shown to its attendees")
                .isFalse();
        assertThat(date.sessions())
                .as("the vacated 09:00 slot must not come back")
                .noneMatch(session -> session.startTime().equals(originalStart));
    }

    /**
     * A moved session that still has seats must be fillable at its new time, and the booking must
     * land on the session that moved rather than materializing a second one beside it.
     */
    @Test
    void movedSessionWithSeatsLeft_isBookableAtItsNewTime() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 5, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        Instant originalStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        originalStart, "first@test.com", "First Guest"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());

        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                UUID.class, eventType.getId(), java.sql.Timestamp.from(originalStart));

        // 14:00 is outside the 09:00-11:00 window, so the rule generates nothing there.
        Instant offGridStart = TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC);
        sessionService.rescheduleSession(sessionId, host.getId(), offGridStart);

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);
        PublicGroupSessionCardResponse moved = response.dates().get(0).sessions().stream()
                .filter(session -> session.startTime().equals(offGridStart))
                .findFirst()
                .orElseThrow();

        assertThat(moved.spotsLeft()).isEqualTo(4);
        assertThat(moved.bookable())
                .as("4 of 5 seats remain, so guests must be able to take one")
                .isTrue();

        // The advertised card must actually be bookable: display and booking disagreeing would
        // offer a seat the booking path then refuses.
        var second = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        offGridStart, "second@test.com", "Second Guest"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), second.bookingId());

        assertThat(second.sessionId())
                .as("the booking must join the moved session, not create a second one")
                .isEqualTo(sessionId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ?",
                Integer.class, eventType.getId()))
                .isEqualTo(1);
    }

    /**
     * "An existing session with seats is bookable at its own time" must not become a way around
     * the booking window. A session materialized beyond maxAdvance is still too far out to sell,
     * and the seat count is no argument against the host's own limit.
     */
    @Test
    void materializedSessionBeyondAdvanceWindow_isNotBookable() {
        User host = createHostWithAvailability();
        EventType eventType = createHourlyGroupType(host.getId(), 5, Duration.ZERO, Duration.ofDays(30));
        insertRecurringWindow(eventType.getId(), "09:00", "11:00");

        Instant sessionStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(),
                new io.bunnycal.booking.dto.PublicBookRequest(
                        sessionStart, "guest@test.com", "Guest Zero"));
        publicBookingService.confirm(host.getUsername(), eventType.getSlug(), hold.bookingId());

        // Shrink the booking window so the already-materialized session falls outside it.
        // max_advance is BIGINT seconds, not an ISO-8601 duration string.
        jdbc.update("UPDATE event_types SET max_advance = ? WHERE id = ?",
                Duration.ofDays(1).getSeconds(), eventType.getId());

        PublicGroupSessionsResponse response = publicGroupSessionQueryService.getGroupSessions(
                host.getUsername(), eventType.getSlug(), TEST_DATE, 1);

        PublicGroupSessionCardResponse card = response.dates().get(0).sessions().stream()
                .filter(session -> session.startTime().equals(sessionStart))
                .findFirst()
                .orElse(null);

        if (card != null) {
            assertThat(card.bookable())
                    .as("beyond maxAdvance, seats left or not")
                    .isFalse();
        }
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
