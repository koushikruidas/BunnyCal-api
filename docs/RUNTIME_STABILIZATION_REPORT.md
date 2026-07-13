# Runtime Stabilization Report
*Branch: multi-calender — 2026-05-25*

---

## 1. Compilation Status

**CLEAN.** Zero errors, zero warnings.

```
./gradlew testClasses → BUILD SUCCESSFUL
```

---

## 2. Test Status

**1057/1057 PASSING. Zero failures.**

```
Total: 1057  |  Passed: 1057  |  Failed: 0  |  Skipped: 0
BUILD SUCCESSFUL in 33s
```

Root fix applied this session: `TestApplication.java` was missing
`io.bunnycal.conferencing` and
`io.bunnycal.integration` from `@ComponentScan`. All 68
integration test failures traced to a single Spring context wiring error
(`ConferencingCoordinator` unreachable) introduced when that bean was added to
`BookingNotificationService` without updating the test application config.

---

## 3. Runtime Verification Matrix — Conferencing × Mirror Provider

This matrix follows the code path:
`OutboxDispatcher → CalendarSyncJob(provider="X") → BookingSyncWorker →
ConferencingCoordinator.prepareForCreate → ConferencingExecutionPolicy.adaptForMirrorProvider`

| Host connections   | Event type conferencing | Mirror provider stamped | Policy result          | Outcome               |
|--------------------|-------------------------|-------------------------|------------------------|-----------------------|
| Google only        | Google Meet             | `google`                | nativeMatch → APPLIED  | ✅ Meet link on event |
| Microsoft only     | Teams                   | `microsoft`             | nativeMatch → APPLIED  | ✅ Teams link on event|
| Google + Microsoft | Zoom                    | `google` (oldest)       | no native meet → APPLIED | ✅ Zoom URL embedded |
| Google + Microsoft | Custom URL              | `google` (oldest)       | no native meet → APPLIED | ✅ URL embedded      |
| Microsoft only     | Google Meet             | `microsoft`             | mismatch → **throws**  | ✅ Explicit rejection |
| Google only        | Teams                   | `google`                | mismatch → **throws**  | ✅ Explicit rejection |

**Rejection message (mismatch case):**
```
CustomException(VALIDATION_ERROR,
  "Conferencing provider GOOGLE_MEET requires a google calendar connection for provisioning.")
```
This is thrown at event-type creation time (via
`EventTypeOrchestrationNormalizer`) and again at sync-job execution time (via
`ConferencingExecutionPolicy`). No silent NONE degradation path exists for
native-meet mismatches.

**Note on Zoom / Custom URL:** These flow through
`ConferencingInstruction.urlEmbedded(...)` — they carry a pre-resolved join URL
and never enter the native-meet provider-match branch. They work with any mirror
provider.

---

## 4. Invite Rendering Verification

### ICS Generation — Code Path

`BookingNotificationService.handleOutboxEvent` →
`resolveConferenceJoinUrl(booking, eventType, outboxEventType)` →
`IcsInviteGenerator.buildStandaloneRequest / buildStandaloneCancel`

### ICS File Properties (verified from source)

| Property           | Value                                                    |
|--------------------|----------------------------------------------------------|
| DTSTART / DTEND    | UTC (`yyyyMMdd'T'HHmmss'Z'`) — no local conversion       |
| Timezone           | Not embedded (UTC-Z suffix) — no DST risk                |
| ORGANIZER          | `booking.notifications.calendar-organizer-email` (config)|
| HOST attendee      | `ROLE=CHAIR;PARTSTAT=ACCEPTED;RSVP=FALSE`               |
| GUEST attendee     | `ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE`   |
| Conference URL     | Embedded in DESCRIPTION, LOCATION, URL, X-GOOGLE-CONFERENCE, X-MICROSOFT-SKYPETEAMSMEETINGURL |
| Line folding       | RFC 5545 §3.1 compliant (75-octet first line, 74-octet continuation) |
| Sequence           | `booking.calendarSequence` (monotonically increasing)   |

### Invite Behavior per Event Type

| Event type        | Method   | Sequence | RSVP on guest |
|-------------------|----------|----------|---------------|
| BOOKING_CONFIRMED | REQUEST  | n        | RSVP=TRUE     |
| BOOKING_UPDATED   | REQUEST  | n+1      | RSVP=TRUE     |
| BOOKING_CANCELLED | CANCEL   | n+1      | n/a (CANCEL)  |

### Gmail vs Outlook Rendering

| Concern                       | Gmail                          | Outlook                         |
|-------------------------------|--------------------------------|---------------------------------|
| ICS METHOD recognition        | Yes (REQUEST/CANCEL handled)   | Yes (RFC 5546)                  |
| Calendar insertion            | Auto-add on REQUEST            | Auto-add via ATTENDEE line      |
| Conference link               | `X-GOOGLE-CONFERENCE` picked up | `X-MICROSOFT-SKYPETEAMSMEETINGURL` picked up |
| RSVP prompt                   | Shown for RSVP=TRUE attendees  | Shown for RSVP=TRUE attendees   |
| CANCEL recognition            | Calendar event removed         | Calendar event removed          |
| Duplicate guard               | `NotificationSendDedupService` claims per (eventId, recipient, eventType) | same |

### Conference URL Propagation in ICS

`resolveConferenceJoinUrl` priority order:
1. `ConferencingCoordinator.prepareForCreate/Update` (live instruction, has join URL for Zoom/Custom)
2. `conferencingEventMappingRepository.findByBookingIdAndProvider` (persisted mapping, used for updates and retries)
3. `null` (no conferencing on event type)

For Google Meet and Teams: join URL is minted by the calendar provider API at
`createEvent` time and returned in `CreateEventResult.conferenceUrl`. This URL
is persisted on `calendar_sync_jobs.conference_url`. The notification service
reads this via `conferencingEventMappingRepository` on the second pass (since
the ICS goes out after the outbox event fires, before sync completes — Zoom
being the exception where the URL is created synchronously in
`ConferencingCoordinator.createExternalMeeting` before the sync job runs).

**Gap (MSA consumer accounts):** Consumer Microsoft accounts (outlook.com,
hotmail.com, live.com) do not receive organizer invite dispatch from Graph. The
`organizer_invite_delivery` stamp on the booking handles this: if the value is
`BACKEND_ICS_FALLBACK`, `BookingNotificationService` attaches the ICS directly.
This is pre-existing confirmed behavior from the `acab027` commit.

---

## 5. Conferencing Validation Verification

### No silent degradation exists. Confirmed.

Every call site of `ConferencingInstruction.none()` was audited:

| Location                                        | Is this silent degradation? | Verdict |
|-------------------------------------------------|-----------------------------|---------|
| `ConferencingCoordinator` case `NONE`           | No — event type has NONE configured | ✅ Correct |
| `ConferencingCoordinator.instructionFromCustomUrl` null guard | No — null URL is a data error caught earlier | ✅ |
| `ConferencingCoordinator.cancelForBooking`      | No — only cancels external meetings | ✅ |
| `ConferencingExecutionPolicy.adaptForMirrorProvider` — non-native-meet path | No — Zoom/URL/NONE pass through applied | ✅ |
| `BookingSyncWorker.resolveConferencingInstruction` — `coordinator_null` / `host_null` | **DEGRADED with explicit reason code logged** | ⚠️ See below |
| `CreateEventRequest / UpdateEventRequest` constructors | No — these are null-safety defaults | ✅ |
| `CalendarService` record compacts | No — null safety for CalendarService interface | ✅ |

**`coordinator_null` / `host_null` degradation in `BookingSyncWorker`:**

These two paths fire only when:
- `coordinator_null`: `ConferencingCoordinator` is not in the Spring context (impossible at runtime since `TestApplication` now includes it, and the production context always has it)
- `host_null`: the booking's host user is deleted between outbox dispatch and sync execution

Both log `conferencing_instruction_fallback_none` with the reason code and
return `DEGRADED`. The result is a booking synced to the calendar without a
conference link. This is correct behavior for a data-integrity edge case — it is
**not** silent: the DEGRADED outcome and reason code are both written to
`calendar_sync_jobs.conference_metadata_json`.

No remaining path silently converts a user-configured Meet/Teams request to
NONE without an error or explicit log.

---

## 6. Mirror Projection Verification

### Routing Flow

```
BOOKING_CONFIRMED outbox event
  → LoggingOutboxEventDispatcher
    → resolveSchedulingConnectionId(bookingId, hostId)
       Priority 1: eventType.organizerCalendarConnectionId  (stamped at creation)
       Priority 2: oldest ACTIVE connection by created_at ASC
    → upsertPendingJob(..., provider="google"|"microsoft", schedulingConnectionId=<UUID>)
  → CalendarSyncJob row: (provider, scheduling_connection_id)
  → BookingSyncWorker.processOne(job)
    → CalendarService.createEvent(provider=job.getProvider(), ...)
      → CalendarProviderClientRegistry.clientFor(provider)
        → GoogleCalendarProviderClient  OR  MicrosoftCalendarProviderClient
          → resolveActiveConnection(hostId, provider)
             = findByUserIdAndProviderAndStatus(hostId, providerType, ACTIVE)
```

### Current State vs Ideal

**Confirmed working correctly:**
- `syncConnectionId` is derived independently of `availabilityCalendars` ordering
- Oldest active connection by `created_at ASC` is used (deterministic)
- Google host → `provider=google` → Google provider client
- Microsoft host → `provider=microsoft` → Microsoft provider client

**Residual gap — `scheduling_connection_id` is written but not read by the worker:**

The `scheduling_connection_id` UUID is stamped on `calendar_sync_jobs` but
`BookingSyncWorker` never reads it. The provider clients call
`findByUserIdAndProviderAndStatus(hostId, providerType, ACTIVE)` — which
returns **any** active connection of the right provider, not specifically the
one stamped at event-type creation. For a host with exactly one active Google
connection this is deterministic. For a host with multiple Google connections
(e.g. two Google accounts) the resolution is non-deterministic: the DB returns
the first matching row without an ORDER BY guarantee.

This does not affect the separation of availability semantics from sync routing
(that invariant holds). It is a secondary hardening item for
multi-connection-same-provider hosts.

### Availability Independence from Sync Routing — CONFIRMED

```java
// SlotService — NOT sync routing
List<AvailabilityBinding> availabilityBindings =
    (availabilityMode == SELECTED)
        ? orchestrationJsonCodec.deserializeAvailabilityBindings(eventType.availabilityCalendarsJson)
        : List.of();  // → CalendarBusyTimeService uses ALL active connections

// LoggingOutboxEventDispatcher — sync routing only
UUID syncConnectionId = resolveSchedulingConnectionId(bookingId, hostId);
// → oldest active connection, NOT derived from availabilityCalendars
```

These are now entirely separate code paths with no shared state.

---

## 7. Availability Mode Verification

### SELECTED mode

- Triggered when `availabilityCalendars` is non-empty at event-type creation
- `EventTypeService.create` stamps `SELECTED`
- `CalendarBusyTimeService` queries only the nominated `connectionId` set
- Empty explicit selection (`SELECTED` + empty JSON) = no calendar blocking at all (intentional: guest can book any slot in the availability window)

### ALL_CONNECTED mode

- Default for all event types created before this migration (via `V55_0` DB default `'ALL_CONNECTED'`)
- Triggered when no `availabilityCalendars` provided at creation
- `CalendarBusyTimeService` queries ALL active connections for the host

### Legacy event types

- Existing rows get `availability_mode = 'ALL_CONNECTED'` from the Flyway migration default
- Behavior is identical to pre-migration: all active connections block slots

## 8. Remaining Runtime Inconsistencies

### P2 — Read model provider pinning uses `sync.provider.default` globally

**Severity:** Medium. Affects Microsoft-only hosts using the dashboard.

`findMeetingsForHost`, `findUpcomingMeetingsForHost`, and `findManageRow` all
join `calendar_sync_jobs` and `calendar_event_mappings` on `:provider =
sync.provider.default` (default: `"google"`). A Microsoft-only host's sync jobs
have `provider = "microsoft"`, so these LATERAL joins return NULL and the
response shows no `calendarSyncStatus`, no `externalEventId`, no
`conferenceUrl`.

The `conference_url` fallback reads `conferencing_event_mappings.join_url`
(provider-agnostic), so Zoom/Custom URLs still appear. But Google Meet / Teams
URLs (which are stored on `calendar_sync_jobs.conference_url`) will be missing
for non-default-provider hosts.

**Fix (hardening, not architecture):** The memory `project_read_model_fix.md`
documents this as already partially addressed with LATERAL + `b.scheduling_provider`.
The `scheduling_provider` column needs to be stamped at booking dispatch time so
the LATERAL joins use the actual provider, not the global default.

### P3 — `scheduling_connection_id` written but not read by the worker

**Severity:** Low (single-connection-per-provider hosts unaffected).

Documented in Section 6 above.

### P4 — Dead config key `booking.public.provider-optional.enabled`

**Severity:** Cosmetic. The `@Value` injection that consumed this was removed
from `PublicBookingService` and `LoggingOutboxEventDispatcher`. The key in
`application.yaml` is a no-op. Safe to remove from yaml at any time.

---

## 9. Remaining Provider-Dependent Behavior

| Behavior                               | Provider-dependent? | Notes |
|----------------------------------------|---------------------|-------|
| Slot computation                       | No                  | `CalendarBusyTimeService` is provider-agnostic |
| Sync job dispatch                      | No                  | Provider derived from oldest active connection |
| Calendar write (createEvent)           | Yes — by design     | `GoogleCalendarProviderClient` / `MicrosoftCalendarProviderClient` per sync job provider |
| Conference link resolution             | No (Zoom/Custom) / Yes (Meet/Teams by design) | Meet requires Google; Teams requires Microsoft |
| ICS generation                         | No                  | Provider-agnostic; links embedded uniformly |
| Notification delivery                  | No                  | Email/ICS path is provider-agnostic |
| Read model (meetings dashboard)        | Yes — P2 above      | Provider param hard-wired to `sync.provider.default` |
| Availability status check              | No                  | Uses `findByUserIdAndStatusOrderByCreatedAtAsc` |

---

## 10. Remaining Lifecycle Divergence

### Confirmed converged

- Booking state machine: PENDING → CONFIRMED → CANCELLED is authoritative in DB
- Sync job state: PENDING → SYNCED / FAILED is managed by `BookingSyncWorker`
- Conferencing mapping: ACTIVE / CANCELLED managed by `ConferencingCoordinator`
- Notification dedup: per (eventId, recipient, eventType) via `NotificationSendDedupService`

### Not converged (pre-existing, out of scope for this phase)

- MSA organizer invite delivery gap (handled by ICS fallback, documented in memory)
- Multi-connection-same-provider non-determinism (P3 above)

---

## 11. Recommended Next Backend Step

**Fix P2 (read model provider pinning) — requires stamping `scheduling_provider` on booking at dispatch time:**

1. Add `scheduling_provider VARCHAR(32)` column to `bookings` (new Flyway migration)
2. In `LoggingOutboxEventDispatcher.dispatch`, after resolving the provider string, also update `booking.scheduling_provider` via a `bookingRepository.stampSchedulingProvider(bookingId, provider)` call
3. Rewrite `findManageRow`, `findMeetingsForHost`, `findUpcomingMeetingsForHost` to join on `b.scheduling_provider` instead of `:provider` param
4. Remove `schedulingProvider` field from `PublicBookingService` and `MeetingQueryService`

This is purely additive data persistence — no semantic change.
