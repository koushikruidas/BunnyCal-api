# Frontend–Backend Integration Contract

**Architecture revision:** Phase 3C canonical  
**Status:** Authoritative  

All flows, DTOs, and semantics documented here reflect the **current backend implementation** after Phases 1–3C. Do not derive contracts from earlier documentation or from reading older field names that may appear in code comments or git history.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Event Type Creation Contract](#2-event-type-creation-contract)
3. [Availability & Slot Generation Flow](#3-availability--slot-generation-flow)
4. [Public Booking Flow (Guest)](#4-public-booking-flow-guest)
5. [Booking Confirmation & Provider Projection](#5-booking-confirmation--provider-projection)
6. [Booking Management Flow](#6-booking-management-flow)
7. [Conferencing Rendering Contract](#7-conferencing-rendering-contract)
8. [Host Dashboard Meeting List](#8-host-dashboard-meeting-list)
9. [Lifecycle State Reference](#9-lifecycle-state-reference)
10. [Frontend Migration Checklist](#10-frontend-migration-checklist)
11. [Removed Legacy Contracts](#11-removed-legacy-contracts)

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│  HOST SETUP (authenticated)                              │
│  POST /api/event-types        — create event type        │
│  PUT  /api/availability/rules/bulk  — set schedules      │
│  POST /api/availability/overrides   — block dates        │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────┐
│  GUEST BOOKING FLOW (public, no auth)                    │
│  GET  /public/{user}/{slug}             — event info     │
│  GET  /public/{user}/{slug}/availability — slots         │
│  POST /public/{user}/{slug}/book        — hold slot      │
│  POST /public/{user}/{slug}/book/{id}/confirm — confirm  │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────┐
│  ASYNC BACKEND WORK (not visible to frontend)            │
│  • BookingOwnership created                              │
│  • CalendarSyncJob enqueued                              │
│  • BookingSyncWorker writes to provider calendar         │
│  • ConferencingCoordinator normalizes meeting details    │
│  • BookingNotificationService sends ICS + email          │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────┐
│  HOST MANAGEMENT (authenticated)                         │
│  GET /api/bookings/me/meetings   — meeting dashboard     │
│  POST /api/bookings/{id}/cancel  — host cancels          │
└─────────────────────────────────────────────────────────┘
```

### Key ownership semantics

- **ProjectionDestination** = where calendar events are *written* (a specific calendar on a specific provider connection). One per event type. Required at creation time.
- **AvailabilitySources** = which calendars are *read* to compute free/busy. May be multiple. May differ entirely from the projection destination.
- **BookingOwnership** = the backend record that pins a confirmed booking to a specific provider external event ID. Created on first successful sync. Authoritative for all subsequent update/delete operations on that booking.
- **ConferenceDetails** = the canonical conferencing shape. The only conferencing contract the frontend consumes.

---

## 2. Event Type Creation Contract

### 2.1 Create event type

```
POST /api/event-types
Authorization: Bearer <jwt>
Content-Type: application/json
```

#### Request body — `CreateEventTypeRequest`

```json
{
  "name": "30 Minute Chat",
  "description": "A quick intro call",
  "location": "Google Meet",
  "durationMinutes": 30,
  "bufferBeforeMinutes": 0,
  "bufferAfterMinutes": 5,
  "slotIntervalMinutes": 30,
  "minNoticeMinutes": 60,
  "maxAdvanceDays": 60,
  "holdDurationMinutes": 10,
  "slug": "30min",
  "availabilityCalendars": [
    {
      "connectionId": "550e8400-e29b-41d4-a716-446655440000",
      "provider": "google",
      "externalCalendarId": "primary"
    }
  ],
  "conference": {
    "enabled": true,
    "provider": "GOOGLE_MEET",
    "customUrl": null
  },
  "projectionDestination": {
    "provider": "google",
    "connectionId": "550e8400-e29b-41d4-a716-446655440000",
    "calendarId": "primary"
  }
}
```

#### Field reference

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `name` | string | **yes** | Display name |
| `durationMinutes` | integer | **yes** | Must be > 0 |
| `slotIntervalMinutes` | integer | **yes** | Must be > 0 |
| `bufferBeforeMinutes` | integer | **yes** | Must be ≥ 0 |
| `bufferAfterMinutes` | integer | **yes** | Must be ≥ 0 |
| `minNoticeMinutes` | integer | **yes** | Must be ≥ 0 |
| `maxAdvanceDays` | integer | **yes** | Must be > 0 |
| `holdDurationMinutes` | integer | **yes** | Must be > 0 |
| `description` | string | no | Trimmed; null if blank |
| `location` | string | no | Display text only |
| `slug` | string | no | Auto-generated from name if omitted |
| `availabilityCalendars` | array | no | Empty/absent = use ALL connected calendars for free/busy |
| `conference` | object | no | See conferencing variants below |
| `projectionDestination` | object | **yes** | Where events are written; required |

#### `projectionDestination` — required, all three fields mandatory

```json
{
  "provider": "google",          // "google" or "microsoft"
  "connectionId": "<uuid>",      // must be an ACTIVE calendar connection owned by the authenticated user
  "calendarId": "primary"        // must exist and be writable (syncEnabled=true)
}
```

**Critical distinction:**
- `projectionDestination` = the calendar that receives created/updated/deleted events from confirmed bookings. The backend writes to this calendar.
- `availabilityCalendars` = calendars read to compute free/busy for slot generation. The backend reads from these calendars.

They may point to different connections and calendars. They may point to the same connection. They are independent.

#### `conference` — conferencing variants

**No conferencing:**
```json
{ "enabled": false }
```

**Google Meet (native):**
```json
{ "enabled": true, "provider": "GOOGLE_MEET" }
```

**Microsoft Teams (native):**
```json
{ "enabled": true, "provider": "MICROSOFT_TEAMS" }
```

**Zoom:**
```json
{ "enabled": true, "provider": "ZOOM" }
```

**Custom URL (static link):**
```json
{ "enabled": true, "provider": "CUSTOM_URL", "customUrl": "https://zoom.us/j/12345" }
```

Valid `provider` values: `GOOGLE_MEET` | `MICROSOFT_TEAMS` | `ZOOM` | `CUSTOM_URL`

#### Response — `EventTypeSummaryResponse`

```json
{
  "success": true,
  "data": {
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "name": "30 Minute Chat",
    "slug": "30min",
    "link": "/koushik/30min",
    "availabilityCalendars": [
      {
        "connectionId": "550e8400-e29b-41d4-a716-446655440000",
        "provider": "google",
        "externalCalendarId": "primary"
      }
    ],
    "conference": {
      "enabled": true,
      "provider": "GOOGLE_MEET"
    },
    "projectionDestination": {
      "provider": "google",
      "connectionId": "550e8400-e29b-41d4-a716-446655440000",
      "calendarId": "primary"
    }
  }
}
```

### 2.2 List event types

```
GET /api/event-types
Authorization: Bearer <jwt>
```

Response: same structure, `data` is an array of `EventTypeSummaryResponse`.

### 2.3 Availability rules

```
PUT /api/availability/rules/bulk
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "rules": [
    { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" },
    { "dayOfWeek": "TUESDAY", "startTime": "09:00", "endTime": "17:00" }
  ]
}
```

Response: array of `AvailabilityRuleResponse` with `id`, `dayOfWeek`, `startTime`, `endTime`.  
This is a full-replace operation — existing rules for the user are deleted and replaced by the submitted list.

### 2.4 Availability overrides

```
POST /api/availability/overrides
Authorization: Bearer <jwt>

{
  "date": "2026-06-15",
  "isAvailable": false
}
```

To mark a day as available outside normal hours:
```json
{
  "date": "2026-06-20",
  "isAvailable": true,
  "startTime": "10:00",
  "endTime": "14:00"
}
```

```
GET /api/availability/overrides?from=2026-06-01&to=2026-06-30
DELETE /api/availability/overrides/{id}
```

---

## 3. Availability & Slot Generation Flow

### 3.1 Public availability endpoint (guest-facing)

```
GET /public/{username}/{eventTypeSlug}/availability?date=2026-06-10
```

No authentication required.

#### Response — `SlotResponse`

```json
{
  "success": true,
  "data": {
    "userId": "123e4567-e89b-12d3-a456-426614174000",
    "eventTypeId": "456e7890-e89b-12d3-a456-426614174000",
    "date": "2026-06-10",
    "timezone": "America/New_York",
    "version": 1,
    "generatedAt": "2026-06-10T09:00:00Z",
    "degraded": false,
    "status": "AVAILABLE",
    "slots": [
      { "slotId": "abc123", "start": "2026-06-10T13:00:00Z", "end": "2026-06-10T13:30:00Z" },
      { "slotId": "def456", "start": "2026-06-10T13:30:00Z", "end": "2026-06-10T14:00:00Z" }
    ]
  }
}
```

#### `status` values

| Value | Meaning | UI guidance |
|-------|---------|-------------|
| `AVAILABLE` | Slots returned, calendar is current | Show slots normally |
| `NO_SLOTS_AVAILABLE` | No open slots for that date | Show "no availability" |
| `STALE_CALENDAR_DATA` | Calendar synced > 120s ago | Show slots with staleness warning |
| `CALENDAR_NOT_CONNECTED` | Host has no active calendar connection | Show "calendar not connected" |
| `CALENDAR_SYNC_IN_PROGRESS` | First sync not yet complete | Show "calendar syncing" |

**`degraded: true`** = returned when `status` is anything other than `AVAILABLE`. Slots may still be present. The frontend must check `status`, not assume that `degraded: false` means all is well.

#### How slots are computed (internal)

1. **Availability rules** — host's weekly schedule (Monday 9–5, etc.)
2. **Overrides** — date-specific additions or blocks
3. **Busy intervals** — projection of the host's calendar events (from `availability_calendars` sources), refreshed via webhook/polling
4. **Confirmed bookings** — already-confirmed bookings block their time windows
5. **Buffer times** — `bufferBeforeMinutes` and `bufferAfterMinutes` extend the busy window around each existing booking
6. **Min notice** — slots within `minNoticeMinutes` from now are excluded
7. **Max advance** — slots beyond `maxAdvanceDays` in the future are excluded

The slot engine is purely deterministic. It does not call provider APIs live. All busy intervals come from the database projection.

### 3.2 Admin/internal slot endpoint (debug mode)

```
GET /api/users/{userId}/event-types/{eventTypeId}/slots?date=2026-06-10&debug=true
Authorization: Bearer <jwt>
```

`debug=true` includes additional trace metadata in the response via `SlotDebugTrace`. Use during development only — not for production UI rendering.

---

## 4. Public Booking Flow (Guest)

### 4.1 Event info

```
GET /public/{username}/{eventTypeSlug}
```

#### Response — `PublicEventInfoResponse`

```json
{
  "success": true,
  "data": {
    "name": "30 Minute Chat",
    "duration": 30,
    "timezone": "America/New_York",
    "hostName": "Koushik",
    "hostUsername": "koushik",
    "description": "A quick intro call",
    "location": "Google Meet",
    "hostAvatarUrl": "https://cdn.example.com/avatar.png"
  }
}
```

### 4.2 Hold a slot

```
POST /public/{username}/{eventTypeSlug}/book
Idempotency-Key: <client-generated-uuid>  (optional but recommended)
X-Timezone: America/New_York              (optional)
Content-Type: application/json

{
  "startTime": "2026-06-10T13:00:00Z",
  "guestEmail": "guest@example.com",
  "guestName": "Alice Smith"
}
```

`startTime` must be an ISO-8601 instant (UTC). The backend derives `endTime` from the event type's `durationMinutes`.

`Idempotency-Key` prevents duplicate bookings on retry — use the same key for retries of the same booking intent.

#### Response — `PublicHoldResponse`

```json
{
  "success": true,
  "data": {
    "bookingId": "789abcde-e89b-12d3-a456-426614174000",
    "startTime": "2026-06-10T13:00:00Z",
    "endTime": "2026-06-10T13:30:00Z",
    "expiresAt": "2026-06-10T13:10:00Z"
  }
}
```

The booking is in `PENDING` status. The slot is reserved until `expiresAt`. The guest must confirm before expiry.

### 4.3 Confirm the booking

```
POST /public/{username}/{eventTypeSlug}/book/{bookingId}/confirm
```

No body required.

#### Response — `PublicConfirmResponse`

```json
{
  "success": true,
  "data": {
    "bookingId": "789abcde-e89b-12d3-a456-426614174000",
    "status": "SYNCING",
    "manageToken": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```

`status: "SYNCING"` — the booking is confirmed in the database. Async work (calendar event creation, conferencing setup, email delivery) starts now in the background. The frontend does not poll for sync completion.

`manageToken` — a time-limited capability token (default 14 days). Store this on the client. Required for the manage view, reschedule, and guest-initiated cancel.

### 4.4 Manage view (guest)

```
GET /public/{username}/{eventTypeSlug}/book/{bookingId}?token={manageToken}
```

#### Response — `PublicManageBookingResponse`

```json
{
  "success": true,
  "data": {
    "bookingId": "789abcde-e89b-12d3-a456-426614174000",
    "eventTitle": "30 Minute Chat",
    "durationMinutes": 30,
    "startTime": "2026-06-10T13:00:00Z",
    "endTime": "2026-06-10T13:30:00Z",
    "hostName": "Koushik",
    "hostUsername": "koushik",
    "hostAvatarUrl": "https://cdn.example.com/avatar.png",
    "attendeeName": "Alice Smith",
    "attendeeEmail": "guest@example.com",
    "conferenceDetails": {
      "provider": "GOOGLE_MEET",
      "joinUrl": "https://meet.google.com/abc-defg-hij",
      "dialIn": null,
      "meetingCode": null,
      "password": null,
      "sourceOfTruth": "projection"
    },
    "status": "CONFIRMED",
    "externalLifecycleState": "STABLE",
    "externalLifecycleReason": null,
    "timezone": "America/New_York"
  }
}
```

### 4.5 Guest cancel

```
POST /public/{username}/{eventTypeSlug}/book/{bookingId}/cancel?token={manageToken}
Idempotency-Key: <uuid>  (optional)
```

#### Response — `PublicBookingStatusResponse`

```json
{
  "success": true,
  "data": {
    "bookingId": "789abcde-...",
    "status": "CANCELLED",
    "startTime": "2026-06-10T13:00:00Z",
    "endTime": "2026-06-10T13:30:00Z",
    "expiresAt": null
  }
}
```

### 4.6 Guest reschedule

```
POST /public/{username}/{eventTypeSlug}/book/{bookingId}/reschedule?token={manageToken}
Idempotency-Key: <uuid>
X-Timezone: America/New_York
Content-Type: application/json

{
  "startTime": "2026-06-11T14:00:00Z"
}
```

Response: `PublicBookingStatusResponse` with `status: "CONFIRMED"`.

---

## 5. Booking Confirmation & Provider Projection

This section documents what happens server-side after `POST .../confirm`. The frontend does not observe these steps directly.

```
Guest confirms booking
        │
        ▼
BookingService.confirm()
  • Booking status: PENDING → CONFIRMED
  • BookingOwnership record created
    (pins booking to projectionDestination provider)
  • OutboxEvent published: BOOKING_CONFIRMED
  • CalendarSyncJob enqueued (desired_action = CREATE)
        │
        ▼  (async)
BookingSyncWorker.process()
  • ConferencingCoordinator.prepareForCreate()
    → returns ConferencingInstruction (provider-agnostic)
  • CalendarService.createEvent(ConferencingInstruction)
    → provider adapter writes to projection calendar
    → extracts provider response
  • ConferenceDetails built from instruction + provider result
    (stored as conference_metadata_json in calendar_sync_jobs)
  • BookingOwnership.externalEventId linked
        │
        ▼  (async)
BookingNotificationService.handleOutboxEvent()
  • ConferenceDetails resolved from conferencing coordinator
    or conferencing_event_mappings fallback
  • ICS invite generated (ConferenceDetails.joinUrl embedded)
  • Email sent to host + guest with ICS attachment
```

### What `bookingId` is authoritative for

`bookingId` is the stable identifier for a booking across its entire lifecycle. It does not change on reschedule. Use it to:
- Retrieve manage view
- Cancel/reschedule
- Identify ICS invite UIDs (format: `booking-{bookingId}@{uidDomain}`)

### What `manageToken` is authoritative for

The `manageToken` from `PublicConfirmResponse` is a signed capability token scoped to `(bookingId, hostId, MANAGE_BOOKING)`. It is required for all guest-initiated operations after confirmation. It expires in 14 days by default. The frontend must persist it.

---

## 6. Booking Management Flow

### 6.1 Host cancel

```
POST /api/bookings/{bookingId}/cancel
Authorization: Bearer <jwt>
Idempotency-Key: <uuid>  (optional)
```

Response: `BookingResponse` with the booking's updated state.

### 6.2 Host meeting list

Covered in §8.

---

## 7. Conferencing Rendering Contract

### The rule

**The frontend renders conferencing exclusively through `ConferenceDetailsResponse`.** No other conferencing field exists in any API response. Do not attempt to derive a join URL from provider names, event URLs, or any other field.

### `ConferenceDetailsResponse` fields

```typescript
interface ConferenceDetailsResponse {
  provider: string;       // "GOOGLE_MEET" | "MICROSOFT_TEAMS" | "ZOOM" | "CUSTOM_URL" | "NONE"
  joinUrl: string | null; // The URL to present to users. Null when no conferencing.
  dialIn: string | null;  // Dial-in number, if provided by the conferencing service.
  meetingCode: string | null; // Meeting code/ID, if applicable.
  password: string | null;    // Meeting password, if applicable.
  sourceOfTruth: string;  // Internal provenance tag — for debugging only, not for UI display.
}
```

### Rendering rules

```
if conferenceDetails.provider == "NONE" or joinUrl == null:
  → Do not render any conferencing UI
  
if joinUrl != null:
  → Render a "Join meeting" button/link pointing to joinUrl
  → Optionally show provider logo based on provider enum
  → Optionally show dialIn and meetingCode if non-null
```

**Do not:**
- Branch on `provider` to determine join URL format
- Attempt to construct join URLs from other fields
- Assume `GOOGLE_MEET` always has a URL immediately after confirm (sync is async)
- Use `sourceOfTruth` for any UI logic

**Conference may be null during async window:** Between `confirm` and the first successful provider sync, `conferenceDetails.joinUrl` may be null even for conferencing-enabled event types. The frontend should handle null joinUrl gracefully (e.g., "Meeting link will appear shortly").

### Conferencing by provider — what backend normalizes

| Provider | How meeting is created | What arrives in `joinUrl` |
|----------|----------------------|--------------------------|
| `GOOGLE_MEET` | Google Calendar API creates Meet natively during event creation | `https://meet.google.com/...` |
| `MICROSOFT_TEAMS` | Microsoft Graph API creates Teams meeting natively during event creation | `https://teams.microsoft.com/l/meetup-join/...` |
| `ZOOM` | Zoom API creates meeting separately; URL embedded in calendar event | `https://zoom.us/j/...` |
| `CUSTOM_URL` | Static URL from event type configuration | Whatever was configured |
| `NONE` | No conferencing | `null` |

The frontend does not need to know which path was taken. `joinUrl` is the authoritative answer.

---

## 8. Host Dashboard Meeting List

### Endpoint

```
GET /api/bookings/me/meetings?upcomingOnly=true&limit=50
Authorization: Bearer <jwt>
```

Or for a specific host (admin use):
```
GET /api/bookings/hosts/{hostId}/meetings?upcomingOnly=true&limit=50
Authorization: Bearer <jwt>
```

`upcomingOnly` defaults to `true`. `limit` max is 200, default is 50.

### Response — array of `MeetingSummaryResponse`

```json
{
  "success": true,
  "data": [
    {
      "bookingId": "789abcde-e89b-12d3-a456-426614174000",
      "eventTypeId": "456e7890-e89b-12d3-a456-426614174000",
      "eventTypeName": "30 Minute Chat",
      "startTime": "2026-06-10T13:00:00Z",
      "endTime": "2026-06-10T13:30:00Z",
      "bookingStatus": "CONFIRMED",
      "guestEmail": "guest@example.com",
      "guestName": "Alice Smith",
      "provider": "google",
      "calendarSyncStatus": "CREATED",
      "externalEventId": "abc123xyz_google",
      "providerEventUrl": "https://www.google.com/calendar/event?eid=...",
      "conferenceDetails": {
        "provider": "GOOGLE_MEET",
        "joinUrl": "https://meet.google.com/abc-defg-hij",
        "dialIn": null,
        "meetingCode": null,
        "password": null,
        "sourceOfTruth": "projection"
      },
      "externalLifecycleState": "STABLE",
      "externalLifecycleReason": null,
      "reconcileSuppressed": false,
      "actionRequired": false
    }
  ]
}
```

### Field reference

| Field | Type | Notes |
|-------|------|-------|
| `bookingId` | UUID | Stable booking identifier |
| `eventTypeId` | UUID | Which event type was booked |
| `eventTypeName` | string | Display name |
| `startTime` / `endTime` | ISO-8601 instant | UTC |
| `bookingStatus` | string | `CONFIRMED` \| `CANCELLED` \| `PENDING` |
| `guestEmail` / `guestName` | string | Guest contact info |
| `provider` | string | Calendar provider: `google` \| `microsoft` (lowercase) |
| `calendarSyncStatus` | string | `CREATED` \| `UPDATED` \| `DELETED` \| `SYNCING` (internal) |
| `externalEventId` | string | Provider's event ID (for debug/deep link use only) |
| `providerEventUrl` | string | Deep link to event in provider calendar (may be null) |
| `conferenceDetails` | object | See §7 — canonical conferencing shape |
| `externalLifecycleState` | string | See §9 |
| `externalLifecycleReason` | string | Raw internal error code — for debug display only |
| `reconcileSuppressed` | boolean | True when the booking has entered a terminal external state |
| `actionRequired` | boolean | True when manual host intervention is needed |

---

## 9. Lifecycle State Reference

`externalLifecycleState` appears in both `MeetingSummaryResponse` and `PublicManageBookingResponse`.

| Value | Meaning | Recommended UI |
|-------|---------|----------------|
| `STABLE` | Booking and provider calendar event are in sync | Normal rendering |
| `ACTIVE_DRIFT` | A drift condition detected; reconciliation pending | Optional warning badge |
| `TERMINAL_EXTERNAL_DELETE` | Guest/host deleted the calendar event at the provider | Show as `CANCELLED`; `bookingStatus` is also `CANCELLED` |
| `EXTERNAL_ACTION_REQUIRED` | Provider state is inconsistent; needs host attention | Show warning + `actionRequired: true` |
| `PROVIDER_STATE_ORPHANED` | No matching provider event found after timeout | Show warning + `actionRequired: true` |

### Cancellation rendering rules

When `bookingStatus == "CANCELLED"`:
- Suppress the join meeting link regardless of `conferenceDetails.joinUrl`
- Show cancelled state UI
- Do not offer reschedule option

When `externalLifecycleState == "TERMINAL_EXTERNAL_DELETE"`:
- Treat identically to `bookingStatus == "CANCELLED"`
- The backend surfaces `bookingStatus` as `CANCELLED` in this state as well

When `reconcileSuppressed == true`:
- The booking is in a terminal external state
- Do not offer host sync/retry UI

When `actionRequired == true`:
- Surface a host-facing action badge or warning
- States: `EXTERNAL_ACTION_REQUIRED` or `PROVIDER_STATE_ORPHANED`

---

## 10. Frontend Migration Checklist

### DTOs changed

- [ ] **`MeetingSummaryResponse`** — `conferenceUrl: string` field removed. Read conference join URL from `conferenceDetails.joinUrl`.
- [ ] **`PublicManageBookingResponse`** — `conferenceUrl: string` field removed. Read conference join URL from `conferenceDetails.joinUrl`.
- [ ] Both responses now expose **only** `conferenceDetails: ConferenceDetailsResponse`. There is no fallback field.

### APIs

- [ ] Slot generation: do not call provider APIs directly. Use `/public/{user}/{slug}/availability` — the backend serves from its projection.
- [ ] `POST .../confirm` now returns `manageToken` — **persist this client-side**. All subsequent guest operations require it.
- [ ] `POST .../book` returns `PublicHoldResponse` (not a full booking). A separate `POST .../confirm` step is required.

### Removed fields

The following fields **no longer exist** in any API response. Any code that reads them will either get a compile error (typed clients) or silently receive `undefined`:

| Removed field | Replacement |
|--------------|-------------|
| `MeetingSummaryResponse.conferenceUrl` | `MeetingSummaryResponse.conferenceDetails.joinUrl` |
| `PublicManageBookingResponse.conferenceUrl` | `PublicManageBookingResponse.conferenceDetails.joinUrl` |

### Conferencing rendering changes

- [ ] Remove any code that reads `conferenceUrl` directly from booking responses
- [ ] Remove any code that branches on `provider == "google"` to infer conferencing URL format
- [ ] Remove any code that checks for `hangoutLink`, `joinWebUrl`, or `conferenceData` — these provider-native fields are never exposed outside the backend adapter layer
- [ ] All conferencing UI must derive the join URL from `conferenceDetails.joinUrl`
- [ ] Handle `conferenceDetails.joinUrl == null` gracefully — it is null both when conferencing is disabled (`provider == "NONE"`) and transiently while async sync is in progress

### Event type creation changes

- [ ] `projectionDestination` is **required** — requests without it are rejected with `VALIDATION_ERROR`
- [ ] `projectionDestination.provider` must match the provider of `projectionDestination.connectionId`
- [ ] `availabilityCalendars` and `projectionDestination` are independent — do not assume the projection calendar is also an availability source

### Lifecycle rendering changes

- [ ] Render `TERMINAL_EXTERNAL_DELETE` as cancelled (equivalent to `bookingStatus == "CANCELLED"`)
- [ ] Render `actionRequired: true` with a host-facing warning badge
- [ ] Do not show join link when `bookingStatus == "CANCELLED"` regardless of `conferenceDetails` content

---

## 11. Removed Legacy Contracts

The following were removed in Phases 1–3C and no longer exist anywhere in the system.

### Removed API response fields

| Field | Was on | Phase removed |
|-------|--------|---------------|
| `conferenceUrl` (String) | `MeetingSummaryResponse` | 3C |
| `conferenceUrl` (String) | `PublicManageBookingResponse` | 3C |
| Compatibility constructors bridging `conferenceUrl → conferenceDetails` | Both DTOs | 3C |

### Removed conferencing semantics

- **Dual conferencing model** — prior architecture exposed both a raw `conferenceUrl` string and a `conferenceDetails` object. The raw string is gone.
- **Provider-shaped conferencing fields downstream** — `hangoutLink`, `joinWebUrl`, `conferenceData` are internal to calendar adapter clients. They never appear in any API response or downstream service.
- **Provider-conditional conferencing branches in notifications** — `BookingNotificationService` and `IcsInviteGenerator` consume only `ConferenceDetails`. They do not branch on provider type.

### Removed assumptions

- **Auto-fallback projection** — previous versions selected the "oldest active connection" as an implicit projection destination when none was specified. This fallback is removed. `projectionDestination` is explicitly required in the event type creation request.
- **Legacy organizer identity assumptions** — ICS invites always use the configured `calendar-organizer-email` as the ORGANIZER field. The system does not derive organizer from provider connection identity.

---

*Generated from source: Phase 3C canonical architecture. Do not edit manually — derive updates by reading controller and DTO source files.*
