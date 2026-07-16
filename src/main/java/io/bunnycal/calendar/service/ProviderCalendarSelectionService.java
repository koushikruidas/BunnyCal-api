package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import io.bunnycal.calendar.domain.CalendarRole;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The provider-native calendar IDs to poll for a connection.
 *
 * <p>Sync coverage has to match what the availability engine reads, or slots are generated from
 * calendars nobody ever fetched. Both now read the same thing: the calendars the user left flagged
 * {@code checks_availability}, plus the one that receives their bookings ({@code is_selected}) so we
 * can see our own writes come back. Holiday calendars use their per-calendar successful-sync cursor
 * to poll at a slower, configurable cadence (24 hours by default).
 *
 * <p>This used to scan every one of the user's event types and deserialise each one's availability
 * JSON, then guard against three flavours of corrupted calendar id that the old per-event-type
 * storage made possible (a connection UUID stored where a calendar id belonged, and so on). None of
 * that data exists any more, so neither do those failure modes.
 */
@Service
public class ProviderCalendarSelectionService {
    private static final Logger log = LoggerFactory.getLogger(ProviderCalendarSelectionService.class);

    private final CalendarConnectionCalendarRepository calendarInventoryRepository;
    private final CalendarConnectionSyncCursorRepository cursorRepository;
    private final AvailabilityCalendarPolicy availabilityPolicy;
    private final Duration holidaySyncInterval;
    private final Clock clock;

    @Autowired
    public ProviderCalendarSelectionService(
            CalendarConnectionCalendarRepository calendarInventoryRepository,
            CalendarConnectionSyncCursorRepository cursorRepository,
            AvailabilityCalendarPolicy availabilityPolicy,
            @Value("${calendar.sync.holiday-interval:PT24H}") Duration holidaySyncInterval) {
        this(calendarInventoryRepository, cursorRepository, availabilityPolicy,
                holidaySyncInterval, Clock.systemUTC());
    }

    ProviderCalendarSelectionService(
            CalendarConnectionCalendarRepository calendarInventoryRepository,
            CalendarConnectionSyncCursorRepository cursorRepository,
            Duration holidaySyncInterval,
            Clock clock) {
        this(calendarInventoryRepository, cursorRepository, new AvailabilityCalendarPolicy(),
                holidaySyncInterval, clock);
    }

    ProviderCalendarSelectionService(
            CalendarConnectionCalendarRepository calendarInventoryRepository,
            CalendarConnectionSyncCursorRepository cursorRepository,
            AvailabilityCalendarPolicy availabilityPolicy,
            Duration holidaySyncInterval,
            Clock clock) {
        this.calendarInventoryRepository = calendarInventoryRepository;
        this.cursorRepository = cursorRepository;
        this.availabilityPolicy = availabilityPolicy;
        this.holidaySyncInterval = requirePositive(holidaySyncInterval);
        this.clock = clock;
    }

    public Set<String> selectedAvailabilityCalendarIds(CalendarConnection connection) {
        return selectedAvailabilityCalendarIds(connection, null);
    }

    public Set<String> selectedAvailabilityCalendarIds(CalendarConnection connection,
                                                       SyncSourceAttribution syncMode) {
        if (connection == null || connection.getId() == null) {
            return Set.of();
        }
        UUID connectionId = connection.getId();
        String provider = connection.getProvider() == null
                ? "unknown" : connection.getProvider().name().toLowerCase();
        String syncModeTag = syncMode == null ? "unknown" : syncMode.name();

        List<CalendarConnectionCalendar> inventory =
                calendarInventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId);

        // Holidays ride along with the primary: we pull them to surface days-off, but only while the
        // primary is actually being checked. Turn the primary off and the holiday sync stops too —
        // there is nothing to show days-off against.
        boolean primaryChecksAvailability = inventory.stream()
                .anyMatch(c -> availabilityPolicy.contributesToAvailability(connection, c));
        boolean hasVisibleHolidayCalendar = primaryChecksAvailability && inventory.stream()
                .anyMatch(c -> c.getCalendarRole() == CalendarRole.HOLIDAY && !c.isHidden());
        Map<String, Instant> lastSyncedByCalendarId = hasVisibleHolidayCalendar
                ? lastSyncedByCalendarId(connectionId)
                : Map.of();
        Instant holidayDueBefore = clock.instant().minus(holidaySyncInterval);

        Set<String> selected = new LinkedHashSet<>();
        int selectedHolidayCount = 0;
        int availabilityCalendarCount = 0;
        int writebackCalendarCount = 0;
        for (CalendarConnectionCalendar cal : inventory) {
            if (cal.isHidden()) continue;
            // Availability and writeback are independent reasons to sync. Their sets are composed
            // here, at the orchestration boundary; neither policy changes the other one's meaning.
            String calId = cal.getExternalCalendarId();
            if (calId == null || calId.isBlank()) continue;
            String normalizedCalId = calId.trim();

            boolean holidayDue = cal.getCalendarRole() == CalendarRole.HOLIDAY
                    && primaryChecksAvailability
                    && isHolidayDue(lastSyncedByCalendarId.get(normalizedCalId), holidayDueBefore);
            boolean availabilityWanted = availabilityPolicy.contributesToAvailability(connection, cal);
            boolean writebackWanted = connection.isDefaultWriteback() && cal.isSelected();
            boolean wanted = cal.getCalendarRole() == CalendarRole.HOLIDAY
                    ? holidayDue
                    : availabilityWanted || writebackWanted;
            if (!wanted) continue;
            if (availabilityWanted) availabilityCalendarCount++;
            if (writebackWanted) writebackCalendarCount++;
            if (selected.add(normalizedCalId) && cal.getCalendarRole() == CalendarRole.HOLIDAY) {
                selectedHolidayCount++;
            }
        }

        if (selected.isEmpty()) {
            log.info("provider_calendar_selection_empty provider={} connectionId={} syncMode={} "
                            + "inventorySize={} reason=no_calendar_checks_availability",
                    provider, connectionId, syncModeTag, inventory.size());
        } else {
            log.info("provider_calendar_selection provider={} connectionId={} calendarCount={} availabilityCalendarCount={} "
                            + "writebackCalendarCount={} holidayCalendarCount={} policyVersion={} holidayInterval={} syncMode={}",
                    provider, connectionId, selected.size(), availabilityCalendarCount, writebackCalendarCount,
                    selectedHolidayCount, AvailabilityCalendarPolicy.CONTRACT_VERSION,
                    holidaySyncInterval, syncModeTag);
        }
        return selected;
    }

    private Map<String, Instant> lastSyncedByCalendarId(UUID connectionId) {
        Map<String, Instant> result = new HashMap<>();
        for (CalendarConnectionSyncCursor cursor : cursorRepository.findByConnectionId(connectionId)) {
            String calendarId = cursor.getExternalCalendarId();
            if (calendarId != null && !calendarId.isBlank()) {
                result.put(calendarId.trim(), cursor.getLastSyncedAt());
            }
        }
        return result;
    }

    private static boolean isHolidayDue(Instant lastSyncedAt, Instant dueBefore) {
        return lastSyncedAt == null || !lastSyncedAt.isAfter(dueBefore);
    }

    private static Duration requirePositive(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("calendar.sync.holiday-interval must be positive");
        }
        return interval;
    }

    /**
     * Pre-flight guard, still applied by the incremental-sync clients before calling a provider: a
     * calendar id must not be the connection's own UUID. Google ids are opaque and Microsoft's are
     * base64-ish, so neither is ever UUID-shaped — this cannot produce a false positive.
     *
     * <p>The bug it was written for (a connection id persisted where a calendar id belonged, in the
     * event type's availability JSON) is no longer expressible now that the JSON is gone. Kept as a
     * cheap assertion on data coming back from a provider.
     */
    public static boolean isLegacyCorruption(UUID connectionId, String calendarId) {
        if (connectionId == null || calendarId == null) return false;
        return connectionId.toString().equalsIgnoreCase(calendarId);
    }

    public static boolean isUuidShaped(String calendarId) {
        if (calendarId == null) return false;
        try {
            UUID.fromString(calendarId.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
