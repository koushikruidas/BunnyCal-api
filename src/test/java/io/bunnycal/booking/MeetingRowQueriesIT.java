package io.bunnycal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.booking.repository.BookingRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Executes every MeetingRow query against a real database.
 *
 * <p>All four are native SQL, so a column that does not exist -- or one referenced through a
 * lateral join whose inner SELECT never exposed it -- is a runtime failure Postgres raises on
 * first call and nothing catches earlier. Their callers are covered only by unit tests with
 * mocked repositories, which is how {@code csj.conference_provider does not exist} reached a
 * running server with the whole suite green.
 *
 * <p>These assert the projection binds and the conferencing columns come back; the shape of the
 * response around them belongs to the service tests.
 */
class MeetingRowQueriesIT extends AbstractBookingIT {

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void everyMeetingRowQueryRunsAndCarriesTheConferencingColumns() {
        User host = createHost();
        UUID eventTypeId = insertEventType(host.getId());
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        Instant end = start.plus(30, ChronoUnit.MINUTES);
        UUID bookingId = insertBooking(host.getId(), eventTypeId, start, end, "CONFIRMED", 0L);

        // A synced job carrying a Meet link: the platform lives in its own column, distinct from
        // `provider`, which names the calendar the event was written to.
        jdbc.update("""
                INSERT INTO calendar_sync_jobs
                    (id, internal_ref_type, internal_ref_id, provider, desired_action, status,
                     external_event_id, conference_url, conference_provider,
                     attempt_count, next_retry_at, version, ownership_version, created_at, updated_at)
                VALUES (?, 'BOOKING', ?, 'google', 'CREATE', 'SYNCED', 'ext-1',
                        'https://meet.google.com/abc-defg-hij', 'GOOGLE_MEET',
                        0, NOW(), 0, 0, NOW(), NOW())
                """,
                UUID.randomUUID(), bookingId);

        jdbc.update("UPDATE bookings SET scheduling_provider = 'google' WHERE id = ? AND host_id = ?",
                bookingId, host.getId());

        Optional<BookingRepository.MeetingRow> manageByEventType =
                bookingRepository.findManageRowByEventType(bookingId, eventTypeId);
        assertThat(manageByEventType).isPresent();
        assertThat(manageByEventType.get().getConferenceProvider()).isEqualTo("GOOGLE_MEET");
        assertThat(manageByEventType.get().getConferenceUrl())
                .isEqualTo("https://meet.google.com/abc-defg-hij");
        // The calendar the event was written to, which must stay its own answer.
        assertThat(manageByEventType.get().getProvider()).isEqualTo("google");

        Optional<BookingRepository.MeetingRow> manageRow =
                bookingRepository.findManageRow(bookingId, host.getId(), eventTypeId);
        assertThat(manageRow).isPresent();
        assertThat(manageRow.get().getConferenceProvider()).isEqualTo("GOOGLE_MEET");

        var forHost = bookingRepository.findMeetingsForHost(host.getId(), 50);
        assertThat(forHost).anySatisfy(row ->
                assertThat(row.getConferenceProvider()).isEqualTo("GOOGLE_MEET"));

        var upcoming = bookingRepository.findUpcomingMeetingsForHost(host.getId(), Instant.now(), 50);
        assertThat(upcoming).anySatisfy(row ->
                assertThat(row.getConferenceProvider()).isEqualTo("GOOGLE_MEET"));
    }

    /**
     * The platform is reported for every conferencing provider, not just the Google one that
     * happened to be in local data when this was written. Teams and Zoom travel the identical
     * path; only the stored value differs.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "GOOGLE_MEET,https://meet.google.com/abc-defg-hij",
            "MICROSOFT_TEAMS,https://teams.microsoft.com/l/meetup-join/xyz",
            "ZOOM,https://zoom.us/j/123456789",
    })
    void reportsWhicheverPlatformTheSyncRecorded(String storedProvider, String joinUrl) {
        User host = createHost();
        UUID eventTypeId = insertEventType(host.getId(), "DEFAULT");
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        UUID bookingId = insertBooking(host.getId(), eventTypeId, start, start.plus(30, ChronoUnit.MINUTES),
                "CONFIRMED", 0L);
        insertSyncJob(bookingId, "SYNCED", joinUrl, storedProvider);

        var row = bookingRepository.findManageRowByEventType(bookingId, eventTypeId);
        assertThat(row).isPresent();
        assertThat(row.get().getConferenceProvider()).isEqualTo(storedProvider);
        assertThat(row.get().getConferenceUrl()).isEqualTo(joinUrl);
    }

    /**
     * A sync that never recorded a platform -- a failed job, an older row -- falls back to what
     * the event type asks for, so a Zoom event does not report itself as having no online
     * meeting. DEFAULT is excluded deliberately: it points at whatever the host's calendar
     * resolves to and names no platform of its own, so it stays unknown rather than guessed.
     */
    @Test
    void fallsBackToTheEventTypeWhenTheSyncRecordedNoPlatform() {
        User host = createHost();
        UUID zoomEvent = insertEventType(host.getId(), "ZOOM");
        Instant start = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        UUID zoomBooking = insertBooking(host.getId(), zoomEvent, start, start.plus(30, ChronoUnit.MINUTES),
                "CONFIRMED", 0L);
        insertSyncJob(zoomBooking, "FAILED", null, null);

        assertThat(bookingRepository.findManageRowByEventType(zoomBooking, zoomEvent))
                .get()
                .extracting(BookingRepository.MeetingRow::getConferenceProvider)
                .isEqualTo("ZOOM");

        UUID defaultEvent = insertEventType(host.getId(), "DEFAULT");
        // A different hour: one host cannot hold two bookings over the same window.
        Instant laterStart = start.plus(2, ChronoUnit.HOURS);
        UUID defaultBooking = insertBooking(host.getId(), defaultEvent, laterStart,
                laterStart.plus(30, ChronoUnit.MINUTES), "CONFIRMED", 0L);
        insertSyncJob(defaultBooking, "FAILED", null, null);

        assertThat(bookingRepository.findManageRowByEventType(defaultBooking, defaultEvent))
                .get()
                .extracting(BookingRepository.MeetingRow::getConferenceProvider)
                .isNull();
    }

    private void insertSyncJob(UUID bookingId, String status, String conferenceUrl, String conferenceProvider) {
        jdbc.update("""
                INSERT INTO calendar_sync_jobs
                    (id, internal_ref_type, internal_ref_id, provider, desired_action, status,
                     external_event_id, conference_url, conference_provider,
                     attempt_count, next_retry_at, version, ownership_version, created_at, updated_at)
                VALUES (?, 'BOOKING', ?, 'google', 'CREATE', ?, ?, ?, ?,
                        0, NOW(), 0, 0, NOW(), NOW())
                """,
                UUID.randomUUID(), bookingId, status, "ext-" + bookingId, conferenceUrl, conferenceProvider);
        jdbc.update("UPDATE bookings SET scheduling_provider = 'google' WHERE id = ?", bookingId);
    }

    private UUID insertEventType(UUID userId) {
        return insertEventType(userId, "DEFAULT");
    }

    private UUID insertEventType(UUID userId, String conferencingProvider) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_types
                    (id, user_id, name, slug, duration, buffer_before, buffer_after,
                     slot_interval, min_notice, max_advance, conferencing_provider, kind,
                     capacity, published, availability_mode, group_host_notification_mode,
                     created_at, updated_at)
                VALUES (?, ?, 'Meeting Row Probe', ?, 1800000000000, 0, 0,
                        1800000000000, 0, 2592000000000000, ?, 'ONE_ON_ONE',
                        1, TRUE, 'INHERIT', 'SMART_SUMMARY', NOW(), NOW())
                """,
                id, userId, "meeting-row-probe-" + id, conferencingProvider);
        return id;
    }
}
