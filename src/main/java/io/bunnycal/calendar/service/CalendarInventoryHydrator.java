package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.client.ProviderCalendarInventoryEntry;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.CalendarRole;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarInventoryHydrator {
    private static final Logger log = LoggerFactory.getLogger(CalendarInventoryHydrator.class);

    private final CalendarConnectionCalendarRepository inventoryRepository;
    private final GoogleApiClient googleApiClient;
    private final MicrosoftApiClient microsoftApiClient;
    private final TokenRefresher tokenRefresher;
    private final MeterRegistry meterRegistry;

    public CalendarInventoryHydrator(CalendarConnectionCalendarRepository inventoryRepository,
                                     GoogleApiClient googleApiClient,
                                     MicrosoftApiClient microsoftApiClient,
                                     TokenRefresher tokenRefresher,
                                     MeterRegistry meterRegistry) {
        this.inventoryRepository = inventoryRepository;
        this.googleApiClient = googleApiClient;
        this.microsoftApiClient = microsoftApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Hydrate inventory using an already-issued access token. Used during the OAuth callback
     * before the connection is committed, when we hold a freshly-exchanged token.
     */
    @Transactional
    public void hydrateWithAccessToken(CalendarConnection connection, String accessToken) {
        if (connection == null || connection.getId() == null) {
            return;
        }
        try {
            List<ProviderCalendarInventoryEntry> entries = fetchFromProvider(connection.getProvider(), accessToken);
            persist(connection.getId(), connection.getProvider(), entries);
        } catch (RuntimeException ex) {
            meterRegistry.counter("calendar.inventory.hydrate.failure",
                    "provider", providerTag(connection.getProvider()),
                    "stage", "oauth_callback").increment();
            log.warn("provider_calendar_inventory_hydrate_failed connectionId={} provider={} stage=oauth_callback",
                    connection.getId(), connection.getProvider(), ex);
        }
    }

    /**
     * Hydrate inventory using the stored refresh token. Used by the status endpoint when the
     * snapshot is stale. Best-effort: failures are logged and swallowed so the read path is
     * never blocked by a transient provider outage.
     */
    public void hydrateBestEffort(CalendarConnection connection) {
        if (connection == null || connection.getId() == null) {
            return;
        }
        try {
            tokenRefresher.executeWithValidToken(connection.getId(), accessToken -> {
                List<ProviderCalendarInventoryEntry> entries = fetchFromProvider(connection.getProvider(), accessToken);
                persist(connection.getId(), connection.getProvider(), entries);
                return null;
            });
        } catch (RuntimeException ex) {
            meterRegistry.counter("calendar.inventory.hydrate.failure",
                    "provider", providerTag(connection.getProvider()),
                    "stage", "status_refresh").increment();
            log.warn("provider_calendar_inventory_hydrate_failed connectionId={} provider={} stage=status_refresh",
                    connection.getId(), connection.getProvider(), ex);
        }
    }

    private List<ProviderCalendarInventoryEntry> fetchFromProvider(CalendarProviderType provider, String accessToken) {
        return switch (provider) {
            case GOOGLE -> googleApiClient.listCalendars(accessToken);
            case MICROSOFT -> microsoftApiClient.listCalendars(accessToken);
            default -> List.of();
        };
    }

    @Transactional
    public void persist(UUID connectionId, CalendarProviderType provider, List<ProviderCalendarInventoryEntry> entries) {
        if (entries == null) {
            entries = List.of();
        }
        Instant now = Instant.now();
        Map<String, CalendarConnectionCalendar> existing = inventoryRepository
                .findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId).stream()
                .collect(Collectors.toMap(CalendarConnectionCalendar::getExternalCalendarId, c -> c, (a, b) -> a));
        Set<String> keep = new HashSet<>();
        for (ProviderCalendarInventoryEntry entry : entries) {
            if (entry == null || entry.externalCalendarId() == null || entry.externalCalendarId().isBlank()) {
                continue;
            }
            if (provider == CalendarProviderType.GOOGLE) {
                log.info("google_calendar_inventory_entry connectionId={} rawId={} summary={} primary={} accessRoleDerivedWrite={} persistedProviderCalendarId={}",
                        connectionId,
                        entry.externalCalendarId(),
                        entry.name(),
                        entry.primary(),
                        entry.canWrite(),
                        entry.externalCalendarId());
            }
            keep.add(entry.externalCalendarId());
            CalendarConnectionCalendar row = existing.get(entry.externalCalendarId());
            CalendarRole role = CalendarRole.classify(provider, entry);
            boolean isNew = row == null;
            CalendarRole previousRole = isNew ? null : row.getCalendarRole();
            if (isNew) {
                row = new CalendarConnectionCalendar();
                row.setConnectionId(connectionId);
                row.setExternalCalendarId(entry.externalCalendarId());
                // Only the primary checks availability out of the box. Holiday and other calendars
                // must never silently block a user's slots — they start off, and holidays feed
                // days-off through a different path.
                row.setChecksAvailability(role == CalendarRole.PRIMARY);
            } else if (role != CalendarRole.PRIMARY) {
                // Classification changes must never leave a former primary/legacy secondary
                // blocking as busy after it becomes HOLIDAY or OTHER.
                row.setChecksAvailability(false);
            } else if (previousRole != CalendarRole.PRIMARY) {
                // If the provider promotes a different calendar to primary, turn that new primary
                // on once. A calendar that was already primary keeps the user's explicit toggle.
                row.setChecksAvailability(true);
            }
            // Re-classify on every hydration: provider changes and Microsoft renames can move a row
            // between roles, and the availability invariant above follows the new role.
            row.setCalendarRole(role);
            row.setName(entry.name());
            row.setPrimary(entry.primary());
            row.setCanRead(entry.canRead());
            row.setCanWrite(entry.canWrite());
            row.setHidden(entry.hidden());
            row.setLastSyncedAt(now);
            inventoryRepository.save(row);
            log.info("provider_calendar_inventory_entry connectionId={} calendarId={} name={} writable={} primary={}",
                    connectionId, entry.externalCalendarId(), entry.name(), entry.canWrite(), entry.primary());
        }
        if (!keep.isEmpty()) {
            int removed = inventoryRepository.deleteByConnectionIdAndExternalCalendarIdNotIn(connectionId, keep);
            if (removed > 0) {
                log.info("provider_calendar_inventory_pruned connectionId={} removed={}", connectionId, removed);
            }
        }
        log.info("provider_calendar_inventory_loaded connectionId={} provider={} calendarCount={}",
                connectionId, providerTag(provider), keep.size());
        meterRegistry.counter("calendar.inventory.hydrate.success",
                "provider", providerTag(provider)).increment();
    }

    private static String providerTag(CalendarProviderType provider) {
        return provider == null ? "unknown" : provider.name().toLowerCase();
    }
}
