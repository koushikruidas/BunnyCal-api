package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.CalendarRole;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Sync coverage must match what the availability engine reads, or slots get generated from calendars
 * nobody ever fetched.
 */
class ProviderCalendarSelectionServiceTest {

    @Mock private CalendarConnectionCalendarRepository inventoryRepository;
    @Mock private CalendarConnectionSyncCursorRepository cursorRepository;

    private ProviderCalendarSelectionService service;

    private final UUID connectionId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private final Instant now = Instant.parse("2026-07-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProviderCalendarSelectionService(
                inventoryRepository,
                cursorRepository,
                Duration.ofDays(1),
                Clock.fixed(now, ZoneOffset.UTC));
        when(cursorRepository.findByConnectionId(connectionId)).thenReturn(List.of());
    }

    @Test
    void pollsTheCalendarsThatBlockTheUser() {
        stubInventory(
                calendar("work", true, false),
                holiday("holidays"));

        assertThat(service.selectedAvailabilityCalendarIds(connection()))
                .containsExactlyInAnyOrder("work", "holidays");
    }

    @Test
    void skipsHolidayCalendarUntilOneDayAfterItsLastSuccessfulSync() {
        stubInventory(calendar("work", true, false), holiday("holidays"));
        stubCursor("holidays", now.minus(Duration.ofHours(23)));

        assertThat(service.selectedAvailabilityCalendarIds(connection()))
                .containsExactly("work");
    }

    @Test
    void pollsHolidayCalendarOnceItsDailyIntervalIsDue() {
        stubInventory(calendar("work", true, false), holiday("holidays"));
        stubCursor("holidays", now.minus(Duration.ofDays(1)));

        assertThat(service.selectedAvailabilityCalendarIds(connection()))
                .containsExactlyInAnyOrder("work", "holidays");
    }

    @Test
    void webhookDoesNotRepollRecentlySyncedHolidayCalendar() {
        stubInventory(calendar("work", true, false), holiday("holidays"));
        stubCursor("holidays", now.minus(Duration.ofMinutes(5)));

        assertThat(service.selectedAvailabilityCalendarIds(connection(), SyncSourceAttribution.WEBHOOK))
                .containsExactly("work");
    }

    /** We have to read our own writes back, so the write-back target is polled even if it never blocks. */
    @Test
    void pollsTheWritebackCalendarEvenWhenItDoesNotBlock() {
        stubInventory(calendar("bookings", false, true));

        assertThat(service.selectedAvailabilityCalendarIds(connection())).containsExactly("bookings");
    }

    @Test
    void skipsCalendarsTheUserTurnedOff() {
        stubInventory(
                calendar("work", true, false),
                calendar("noisy-family-calendar", false, false));

        Set<String> selected = service.selectedAvailabilityCalendarIds(connection());

        assertThat(selected).containsExactly("work");
        assertThat(selected).doesNotContain("noisy-family-calendar");
    }

    @Test
    void holidaySyncStopsWithThePrimaryToggle() {
        stubInventory(calendar("work", false, false), holiday("holidays"));

        assertThat(service.selectedAvailabilityCalendarIds(connection())).isEmpty();
    }

    @Test
    void otherCalendarNeverSyncsEvenWithALegacyAvailabilityFlag() {
        CalendarConnectionCalendar birthdays = calendar("birthdays", true, false);
        birthdays.setCalendarRole(CalendarRole.OTHER);
        stubInventory(calendar("work", true, false), birthdays);

        assertThat(service.selectedAvailabilityCalendarIds(connection())).containsExactly("work");
    }

    @Test
    void skipsHiddenCalendars() {
        CalendarConnectionCalendar hidden = calendar("hidden", true, false);
        hidden.setHidden(true);
        stubInventory(calendar("work", true, false), hidden);

        assertThat(service.selectedAvailabilityCalendarIds(connection())).containsExactly("work");
    }

    @Test
    void nullConnection_returnsEmpty() {
        assertThat(service.selectedAvailabilityCalendarIds(null)).isEmpty();
    }

    private void stubInventory(CalendarConnectionCalendar... calendars) {
        when(inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(calendars));
    }

    private void stubCursor(String externalCalendarId, Instant lastSyncedAt) {
        CalendarConnectionSyncCursor cursor = new CalendarConnectionSyncCursor();
        cursor.setConnectionId(connectionId);
        cursor.setExternalCalendarId(externalCalendarId);
        cursor.setProvider(CalendarProviderType.GOOGLE);
        cursor.setLastSyncedAt(lastSyncedAt);
        when(cursorRepository.findByConnectionId(connectionId)).thenReturn(List.of(cursor));
    }

    private CalendarConnection connection() {
        CalendarConnection c = new CalendarConnection();
        c.setProvider(CalendarProviderType.GOOGLE);
        setId(c, connectionId);
        return c;
    }

    private static CalendarConnectionCalendar calendar(String externalId,
                                                       boolean checksAvailability,
                                                       boolean writeback) {
        CalendarConnectionCalendar c = new CalendarConnectionCalendar();
        c.setConnectionId(UUID.fromString("00000000-0000-0000-0000-000000000010"));
        c.setExternalCalendarId(externalId);
        c.setChecksAvailability(checksAvailability);
        c.setSelected(writeback);
        c.setHidden(false);
        c.setCalendarRole(CalendarRole.PRIMARY);
        return c;
    }

    private static CalendarConnectionCalendar holiday(String externalId) {
        CalendarConnectionCalendar c = calendar(externalId, false, false);
        c.setCalendarRole(CalendarRole.HOLIDAY);
        return c;
    }

    private static void setId(CalendarConnection connection, UUID id) {
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(connection, id);
        } catch (Exception ignored) {
            // best effort — the field is only needed so the repository stub matches
        }
    }
}
