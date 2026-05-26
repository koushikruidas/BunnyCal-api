# Runtime Convergence Audit

**Scope:** Final convergence cleanup + runtime semantic hardening before frontend full commit.
**Date:** 2026-05-25

---

## Concern 1 — Hidden Sync Routing Semantics

### Current behavior (FIXED)

Previously `syncConnectionId` (the connection used for calendar mirror writes) was derived as:

```
availabilityCalendars[0].connectionId()
```

This silently encoded infrastructure routing into UI ordering. Sending `[microsoft, google]` produced a different mirror target than `[google, microsoft]`.

### Fix implemented

`resolveSyncConnectionId(userId)` now queries:

```java
calendarConnectionRepository
    .findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE)
    .stream().findFirst()
```

**Rule:** sync/mirror connection = oldest active connection by `created_at ASC` — stable, deterministic, independent of `availabilityCalendars` ordering.

`availabilityCalendars` array carries **free/busy semantics only**. Order has no infrastructure meaning.

### Risk analysis of the old behavior

| Risk | Severity |
|---|---|
| Reordering calendars in the UI silently reroutes mirror writes | HIGH |
| Two users with identical settings but different array order get different sync targets | HIGH |
| Mirror target changes on event-type update if the UI rebuilds the array differently | HIGH |
| Frontend test suites cannot reason about sync behavior without knowing ordering rules | MEDIUM |

### Backward compatibility

- Existing event types: `organizerCalendarConnectionId` on the entity is already persisted at creation time. The new logic only affects **new creates and draft mutations**. Existing syncs continue using whatever `schedulingConnectionId` is stored on the `calendar_sync_jobs` row.
- Users with a single active connection: no observable change.
- Users with multiple connections: sync target is now the one connected first (oldest by `created_at`), which is the most stable and expected choice.

### Remaining coupling

`LoggingOutboxEventDispatcher.resolveSchedulingConnectionId()` reads `eventType.getOrganizerCalendarConnectionId()` (set at create time) as priority-1, then falls back to `sync.provider.default` + an active connection lookup. This is correct — the entity field is the durable routing record. The fix above ensures that field is now populated deterministically.

`sync.provider.default` config (`@Value("${sync.provider.default:google}")`) is still used in two places as the fallback provider string when no connection ID is stamped. This is a separate concern and does not create UI ordering coupling.

---

## Concern 2 — Conferencing Provisioning Reality Audit

### Architecture overview

`ConferencingCoordinator.prepareForCreate()` runs **before** the calendar sync worker calls the provider. It produces a `ConferencingInstruction` with one of three modes:

- `NONE` — no conferencing
- `URL_EMBEDDED` — a pre-resolved join URL (Zoom, Custom URL) embedded in the calendar event description/location
- `REQUEST_NATIVE_MEET` — instruction to the calendar client to request provider-native meeting creation

`ConferencingExecutionPolicy.adaptForMirrorProvider()` then checks whether a `REQUEST_NATIVE_MEET` instruction is compatible with the mirror provider (Google/Microsoft) and either passes it through, degrades it to NONE (decoupled mode, default), or passes through with a warning (legacy strict mode).

### Provider-by-provider audit

---

#### Zoom

**Provisioning mechanism:** Standalone OAuth. `ConferencingProvider` (Zoom impl) calls the Zoom Meetings API directly. Returns `joinUrl` + `hostUrl` + `meetingId`. Stored in `conferencing_event_mappings`. Embedded in calendar event as `URL_EMBEDDED`.

**Prerequisites:**
- Zoom OAuth must be connected for the host (`ZoomConferencingOAuthService.status() = "CONNECTED"`)
- No calendar provider dependency — works with any mirror (Google, Microsoft, or no connection)

**Failure modes:**
- Zoom OAuth revoked or expired → `ConferencingProvider.createMeeting()` throws → sync job retries → eventually DLQ
- Zoom API rate limit → caught as `RATE_LIMIT`, retry with backoff
- Meeting creation fails → join URL missing in calendar event and ICS notification — **silent degradation with no user-facing error**

**Support matrix:**

| Login | Calendar | Result |
|---|---|---|
| Microsoft | Outlook | FULLY SUPPORTED |
| Google | Google | FULLY SUPPORTED |
| Mixed | Mixed | FULLY SUPPORTED |
| Any | None | FULLY SUPPORTED |

**Classification: FULLY SUPPORTED** — truly decoupled from calendar provider.

---

#### Custom URL

**Provisioning mechanism:** Static string from `eventType.getCustomConferenceUrl()`. `ConferencingCoordinator.instructionFromCustomUrl()` wraps it as `URL_EMBEDDED`. No API calls.

**Prerequisites:** Valid `https://` URL set at event-type creation time.

**Failure modes:**
- URL stored as null/blank → `instructionFromCustomUrl()` returns `NONE` — conference link absent, no error surfaced
- URL becomes stale/dead after booking — no validation at booking time

**Support matrix:** FULLY SUPPORTED for all provider combinations.

**Classification: FULLY SUPPORTED**

---

#### Google Meet

**Provisioning mechanism:** `REQUEST_NATIVE_MEET` — instructs the calendar client to include `conferenceData.createRequest` in the Google Calendar API event payload. Google creates the Meet link as a side effect of the calendar event creation. The join URL comes back in the `CalendarService.CreateEventResult.conferenceUrl()` response.

**Prerequisites:**
- Mirror provider must be **Google** (a Google calendar connection with write access)
- `conferencing.orchestration.decouple-native-provider-match` must remain `true` (default) OR the mirror provider must be Google

**What happens when mirror provider is not Google (decoupled mode, default):**

`ConferencingExecutionPolicy.adaptForMirrorProvider()` detects `REQUEST_NATIVE_MEET + non-Google mirror` and returns `degraded(ConferencingInstruction.none(), "native_provider_mismatch")`. The conferencing instruction is silently dropped to NONE. **The calendar event is created without any conference link.** No error is returned to the user.

**This is the critical gap:**

| Login | Calendar | Google Meet selected | Result |
|---|---|---|---|
| Google | Google | ✓ | FULLY SUPPORTED — native Meet created |
| Microsoft | Outlook | ✓ | **SILENT FAILURE** — Meet instruction dropped, no link |
| Mixed | Outlook primary | ✓ | **SILENT FAILURE** — depends on which connection becomes sync target |
| Mixed | Google primary | ✓ | FULLY SUPPORTED if Google is oldest active connection |

**Classification: PROVIDER-LIMITED**

The frontend contract must not claim Google Meet is universally selectable. It is **only reliable when the mirror/sync connection is Google**. The current decoupled mode fails silently.

---

#### Microsoft Teams

**Provisioning mechanism:** `REQUEST_NATIVE_MEET` — instructs the calendar client to include `isOnlineMeeting: true` and `onlineMeetingProvider: teamsForBusiness` in the Microsoft Graph event payload. Teams meeting is created as a side effect.

**Prerequisites:**
- Mirror provider must be **Microsoft** (an Outlook calendar connection with write access)
- Same `ConferencingExecutionPolicy` gating as Google Meet — fails silently with non-Microsoft mirror

**Support matrix:**

| Login | Calendar | Teams selected | Result |
|---|---|---|---|
| Microsoft | Outlook | ✓ | FULLY SUPPORTED — Teams meeting created natively |
| Google | Google | ✓ | **SILENT FAILURE** — Teams instruction dropped, no link |
| Mixed | Outlook primary | ✓ | FULLY SUPPORTED if Microsoft is oldest active connection |
| Mixed | Google primary | ✓ | **SILENT FAILURE** |

**Classification: PROVIDER-LIMITED** — symmetric problem to Google Meet.

---

### True support matrix summary

| Conferencing | Mirror = Google | Mirror = Microsoft | Mirror = None |
|---|---|---|---|
| Zoom | FULLY SUPPORTED | FULLY SUPPORTED | FULLY SUPPORTED |
| Custom URL | FULLY SUPPORTED | FULLY SUPPORTED | FULLY SUPPORTED |
| Google Meet | FULLY SUPPORTED | SILENT FAILURE | SILENT FAILURE |
| Microsoft Teams | SILENT FAILURE | FULLY SUPPORTED | SILENT FAILURE |

---

## Concern 3 — Google Meet / Teams Semantic Honesty

### Required backend action

The current `ProviderCapabilityFlags` record has `supportsConferenceProvisioning` but it is set based on whether a provider has OAuth (Zoom = true, Meet/Teams = false). This does **not** communicate the provisioning constraint.

The frontend contract must be updated to reflect:

**Google Meet** — selectable but only provisioned correctly when sync connection = Google. Must add runtime-visibility:

```json
{
  "supportsConferenceProvisioning": false,
  "requiresProviderWritableCalendar": "google"
}
```

**Microsoft Teams** — selectable but only provisioned correctly when sync connection = Microsoft:

```json
{
  "supportsConferenceProvisioning": false,
  "requiresProviderWritableCalendar": "microsoft"
}
```

**Recommended implementation:** Expose the constraint in the UI contract. The frontend should check whether the user's sync connection (oldest active connection = `authority.availabilityProviders[0]` or derivable from provider catalog) matches the requirement. If not, either:
1. Show the option as disabled with a tooltip ("Requires Google calendar connection")
2. Show a warning when selected

This is a UI contract update — no new flags needed in the backend. The existing `ProviderCapabilityFlags.supportsConferenceProvisioning` can be left as-is (false for both, since they rely on calendar side effects). The constraint is documented in `UI_CONTRACT_EVENT_TYPE_PUBLISH.md`.

**What must NOT happen:** Silently creating a booking with no conference link when the user selected Google Meet or Teams. This is the current failure mode.

---

## Concern 4 — Empty availabilityCalendars Semantics

### Current runtime behavior

| Request | Backend behavior |
|---|---|
| `availabilityCalendars: []` or field absent | All active calendar connections checked for busy/free (fallback mode) |
| `availabilityCalendars: [...]` with entries | Only listed connections checked |

### Distinguishing legacy vs intentional

There is currently **no persisted flag** distinguishing:
- Event type created before `availability_calendars_json` existed (JSON column null/empty)
- Event type created after the feature, with empty selection (JSON = `[]`)
- Event type created after the feature, with explicit selection

Both null/empty JSON → `List.of()` → fallback to all connections. They are operationally identical.

### Risk

New users creating an event type without selecting calendars get **all-calendars fallback** silently. This may unintentionally block slots across personal and work calendars they did not intend to include.

### Recommended future direction

Add `availabilityMode` discriminant to `EventType`:

```sql
ALTER TABLE event_types
    ADD COLUMN availability_mode VARCHAR(32) DEFAULT 'ALL_CONNECTED';
-- Values: ALL_CONNECTED | SELECTED
```

- `ALL_CONNECTED` — existing behavior, set for all existing rows via migration default
- `SELECTED` — only use `availability_calendars_json`; empty selection = no busy-time checking

This is **not implemented now** but the migration path is safe: default `ALL_CONNECTED` preserves all existing behavior. New event types would set `SELECTED` when the user explicitly makes or clears a selection.

### Frontend guidance for now

Until `availabilityMode` is implemented:
- If the user saves with no calendars selected, send `availabilityCalendars: []` — backend treats this as all-calendars. This is acceptable but should be surfaced in UX ("Checking all connected calendars").
- If the user explicitly selects a subset, send the selected connections. Backend will use only those.

---

## Concern 5 — Invite / Lifecycle Convergence Status

### Architecture

`BookingNotificationService` is the authoritative invite sender. It:
- Builds a standalone ICS (not provider-native) from scratch using `IcsInviteGenerator`
- Sends to both host and attendee via SMTP
- ICS uses `ORGANIZER` = `booking.notifications.calendar-organizer-email` (application config, NOT the host's email)
- Deduplication via `NotificationSendDedupService`

There is **no provider-native invite dispatch** from the application. The application owns 100% of invite delivery.

### Classification by scenario

---

#### Gmail attendee + Gmail host

| Aspect | Status | Notes |
|---|---|---|
| Invite delivery | FULLY APP-AUTHORITATIVE | ICS sent via SMTP to both |
| Organizer rendering | KNOWN DIVERGENCE | ORGANIZER = app address, not host Gmail. Gmail may show "via BunnyCal" |
| Conference rendering | FULLY SUPPORTED (Zoom/Custom URL) | Join URL in ICS and email body |
| Conference rendering | PROVIDER-LIMITED (Google Meet) | Meet link only present if sync connection = Google AND calendar event created successfully |
| RSVP handling | NOT APP-AUTHORITATIVE | ICS `METHOD:REQUEST` triggers Gmail's calendar accept/decline UI. RSVP status is not read back by the application |
| Duplicate invites | MITIGATED | `NotificationSendDedupService` prevents re-send per `(event_id, recipient, event_type)` |
| Cancellation | FULLY APP-AUTHORITATIVE | `METHOD:CANCEL` ICS sent via SMTP |
| Update | FULLY APP-AUTHORITATIVE | New ICS with incremented `SEQUENCE` |

---

#### Outlook attendee + Outlook host

| Aspect | Status | Notes |
|---|---|---|
| Invite delivery | FULLY APP-AUTHORITATIVE | ICS via SMTP. MIME structure specifically crafted for Outlook (`multipart/mixed` + `multipart/alternative` + `text/calendar` + `ics` attachment). `X-MS-OLK-FORCEINSPECTOROPEN` header set. |
| Organizer rendering | KNOWN DIVERGENCE | ORGANIZER = app address. Outlook shows organizer name from ICS ORGANIZER field. Host appears as attendee only. |
| Conference rendering | FULLY SUPPORTED (Zoom/Custom URL) | URL in ICS `LOCATION` / `DESCRIPTION` and email body |
| Conference rendering | PROVIDER-LIMITED (Teams) | Teams link only present if sync connection = Microsoft |
| RSVP handling | NOT APP-AUTHORITATIVE | ICS triggers Outlook's accept/decline. RSVP not read back. |
| Duplicate invites | MITIGATED | Same dedup mechanism |
| Cancellation | FULLY APP-AUTHORITATIVE | `METHOD:CANCEL` ICS |
| Update | FULLY APP-AUTHORITATIVE | ICS with incremented sequence |

---

#### Consumer MSA (outlook.com / hotmail / live) host

**Known divergence (documented in project memory):** Microsoft Graph does **not** dispatch organizer invite mail for consumer MSA accounts. The application compensates via `BACKEND_ICS_FALLBACK` — `BookingNotificationService` sends the ICS directly, stamping `organizer_invite_delivery = BACKEND_ICS_FALLBACK` on the booking.

This means for MSA hosts, invite delivery is fully app-authoritative. For AAD hosts (corporate accounts), Graph may also dispatch a native invite — potential duplicate. This is currently unresolved but deduplicated on the app side for the app-sent copy.

---

### Lifecycle convergence classification

| Provider | Invite Delivery | Organizer Rendering | RSVP | Cancellation | Conferencing |
|---|---|---|---|---|---|
| Google (any login) | FULLY APP-AUTHORITATIVE | KNOWN DIVERGENCE (app ORGANIZER) | NOT APP-AUTHORITATIVE | FULLY APP-AUTHORITATIVE | Zoom/Custom=FULL, Meet=PROVIDER-LIMITED |
| Microsoft MSA | FULLY APP-AUTHORITATIVE (BACKEND_ICS_FALLBACK) | KNOWN DIVERGENCE | NOT APP-AUTHORITATIVE | FULLY APP-AUTHORITATIVE | Zoom/Custom=FULL, Teams=PROVIDER-LIMITED |
| Microsoft AAD | PARTIALLY CONVERGED (potential Graph+app duplicate) | KNOWN DIVERGENCE | NOT APP-AUTHORITATIVE | FULLY APP-AUTHORITATIVE | Zoom/Custom=FULL, Teams=PROVIDER-LIMITED |
| Zoom | FULLY APP-AUTHORITATIVE | N/A (no identity) | NOT APP-AUTHORITATIVE | FULLY APP-AUTHORITATIVE | FULLY SUPPORTED |
| Google Meet | N/A | N/A | N/A | N/A | PROVIDER-LIMITED |
| Teams | N/A | N/A | N/A | N/A | PROVIDER-LIMITED |

---

## Summary of required frontend contract updates

Based on this audit, `UI_CONTRACT_EVENT_TYPE_PUBLISH.md` must be updated:

1. **Google Meet and Teams** — not universally selectable. Mark as `requiresProviderWritableCalendar`. Frontend must show a constraint indicator when the user's sync connection doesn't match.

2. **Empty availabilityCalendars** — document that this means all-calendars, and suggest UX communicates this ("Checking all connected calendars for availability").

3. **Conferencing for native meet** — when the sync connection (oldest active connection) does not match the native provider (Google Meet needs Google, Teams needs Microsoft), the option should either be disabled or show a warning. Do not silently show the option as working.

4. **ORGANIZER divergence** — do not promise the host's name/email as the calendar organizer in any UI copy. The organizer is the application address.
