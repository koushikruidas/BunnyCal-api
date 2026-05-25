# UI Contract — Availability Convergence

**Version:** post-convergence (multi-calendar availability)  
**Status:** CANONICAL — backend has been updated; UI must converge to this contract.

---

## What changed on the backend

The backend has completed availability runtime convergence:

1. **`availability_calendars_json` is now live** — per-event-type calendar selection actively filters free/busy computation.
2. **`organizer_calendar_connection_id` is internal only** — it is no longer returned in API responses.
3. **Obsolete fields removed from responses:** `orchestrationProvider`, `calendarProvider`, `conferencingProvider` (top-level), `customConferenceUrl` (top-level), `orchestration` wrapper object.
4. **Backward-compatible creation:** `organizerCalendarConnectionId`, `orchestrationProvider`, `calendarProvider` still accepted on POST as deprecated fallback fields but should no longer be sent by new UI code.

---

## API Reference

### POST /api/event-types

#### Request shape (new canonical)

```json
{
  "name": "string",
  "description": "string | null",
  "location": "string | null",
  "durationMinutes": 30,
  "bufferBeforeMinutes": 0,
  "bufferAfterMinutes": 0,
  "slotIntervalMinutes": 30,
  "minNoticeMinutes": 60,
  "maxAdvanceDays": 30,
  "holdDurationMinutes": 10,
  "slug": "string | null",
  "availabilityCalendars": [
    {
      "connectionId": "<uuid>",
      "provider": "google | microsoft",
      "externalCalendarId": "primary | <email> | null"
    }
  ],
  "conference": {
    "enabled": true,
    "provider": "GOOGLE_MEET | MICROSOFT_TEAMS | ZOOM | CUSTOM_URL | null",
    "customUrl": "https://... | null"
  }
}
```

#### Fields: `availabilityCalendars`

| Field | Type | Description |
|---|---|---|
| `connectionId` | UUID | Required. Must be an active calendar connection owned by the user. |
| `provider` | string | `"google"` or `"microsoft"` (lowercase). |
| `externalCalendarId` | string or null | For Google: `"primary"` or the calendar email. For Microsoft: null or specific calendar ID. |

**Semantics:**
- If `availabilityCalendars` is empty or omitted → all active calendar connections contribute to free/busy (backward-compatible default).
- If `availabilityCalendars` is non-empty → ONLY those connections are queried for busy time for this event type.

#### Fields: `conference`

| Field | Type | Description |
|---|---|---|
| `enabled` | boolean | Required. `false` = no conferencing. |
| `provider` | string | Required when `enabled=true`. One of: `GOOGLE_MEET`, `MICROSOFT_TEAMS`, `ZOOM`, `CUSTOM_URL`. |
| `customUrl` | string | Required when `provider=CUSTOM_URL`. Must be an `https://` URL. |

**Conferencing is independent of the calendar provider.** Google Meet can be used with a Microsoft calendar. There is no provider-matching requirement on the frontend.

#### Deprecated request fields (still accepted, do not send from new UI code)

| Field | Replacement |
|---|---|
| `organizerCalendarConnectionId` | Include the connection in `availabilityCalendars` instead. |
| `orchestrationProvider` | Removed concept. |
| `calendarProvider` | Removed concept. |
| `conferencingProvider` | Use `conference.provider`. |
| `customConferenceUrl` | Use `conference.customUrl`. |

---

#### Response shape (new canonical)

```json
{
  "success": true,
  "data": {
    "id": "<uuid>",
    "name": "string",
    "slug": "string",
    "link": "/public/<username>/<slug>",
    "availabilityCalendars": [
      {
        "connectionId": "<uuid>",
        "provider": "google | microsoft",
        "externalCalendarId": "string | null"
      }
    ],
    "conference": {
      "enabled": true,
      "provider": "GOOGLE_MEET",
      "customUrl": null
    }
  }
}
```

**Removed from response:**
- `orchestration` wrapper object — gone entirely.
- `orchestration.organizerCalendarConnectionId` — internal only, not exposed.
- `orchestrationProvider` — removed.
- `calendarProvider` — removed.
- `conferencingProvider` (top-level) — use `conference.provider`.
- `customConferenceUrl` (top-level) — use `conference.customUrl`.

---

### GET /api/event-types

Same response shape as POST. Each event type item:

```json
{
  "id": "<uuid>",
  "name": "string",
  "slug": "string",
  "link": "/public/<username>/<slug>",
  "availabilityCalendars": [...],
  "conference": { "enabled": ..., "provider": ..., "customUrl": ... }
}
```

---

## UI Flows That Must Be Updated

### 1. Event type creation form

**Current (obsolete):**
- "Select your Google calendar" / "Use your Microsoft calendar"
- Single authoritative calendar picker
- Calendar selection conflated with conferencing provider

**New (canonical):**
- "Which calendars should block availability for this meeting type?"
- Multi-select list of the user's connected calendars across all providers
- Each calendar shows: provider icon + account email + calendar name/label
- Separate conferencing section (independent of calendar choice)
- Empty selection = "all connected calendars" (show as default with tooltip)

**UI data requirements:**
- Needs a `GET /api/calendar-connections` endpoint (or equivalent) to list the user's active calendar connections with their external calendar IDs
- Each entry in `availabilityCalendars` requires: `connectionId` (UUID) + `provider` + `externalCalendarId`

### 2. Event type list / detail view

**Current response fields to stop reading:**
- `response.orchestration.organizerCalendarConnectionId` → remove
- `response.orchestrationProvider` → remove
- `response.calendarProvider` → remove
- `response.conferencingProvider` (top-level) → switch to `response.conference.provider`
- `response.customConferenceUrl` (top-level) → switch to `response.conference.customUrl`

**New fields to render:**
- `response.availabilityCalendars` → display as "Checking: Google Work, Outlook Personal" etc.
- `response.conference.enabled` / `response.conference.provider` → render conferencing badge

### 3. Slot availability display (`GET /public/<username>/<slug>/slots`)

No changes to the slot response shape. The runtime now correctly filters by the selected availability calendars — no UI changes needed for the slots themselves.

### 4. Calendar connection management

When a user connects or disconnects a calendar:
- Existing event types with explicit `availabilityCalendars` referencing the disconnected connection will silently return no events for that connection (the connection becomes inactive, so its events are excluded)
- Event types with empty `availabilityCalendars` automatically pick up/lose the connection
- UI should warn when disconnecting a calendar that is referenced by one or more event types

---

## Calendar Connection Shape (for the picker)

The calendar picker needs to list all active connections. Assuming a `GET /api/calendar-connections` endpoint returns:

```json
[
  {
    "connectionId": "<uuid>",
    "provider": "google",
    "accountEmail": "work@company.com",
    "status": "ACTIVE",
    "calendars": [
      { "externalCalendarId": "primary", "label": "Work Calendar", "isPrimary": true },
      { "externalCalendarId": "work@company.com", "label": "Shared Team Calendar", "isPrimary": false }
    ]
  },
  {
    "connectionId": "<uuid>",
    "provider": "microsoft",
    "accountEmail": "personal@outlook.com",
    "status": "ACTIVE",
    "calendars": [
      { "externalCalendarId": null, "label": "Outlook Calendar", "isPrimary": true }
    ]
  }
]
```

Each entry in the picker maps to:
```json
{
  "connectionId": "<connection uuid>",
  "provider": "google",
  "externalCalendarId": "primary"
}
```

---

## Conceptual Model the UI Should Reflect

```
User identity (login only)
     ↓
Connected calendars  [Google Work] [Google Personal] [Outlook Work]
     ↓
Per-event-type selection:
  Event Type A  → checks: [Google Work] + [Outlook Work]
  Event Type B  → checks: [Google Personal] only
  Event Type C  → checks: all (default)
     ↓
Conferencing (independent):
  Event Type A  → Google Meet
  Event Type B  → Microsoft Teams
  Event Type C  → Zoom
```

Conferencing choice has NO dependency on which calendar is selected. The UI must not enforce or imply any coupling between calendar provider and conferencing provider.

---

## Migration Notes for Existing Event Types

Event types created before this release have `availabilityCalendarsJson = []` (empty). The backend treats this as "use all active connections" — backward-compatible behavior preserved. The UI can show these as "All calendars" and let users refine them.

---

## Error Codes (no change)

| Code | Meaning |
|---|---|
| `VALIDATION_ERROR` | Invalid field value, e.g. inactive connectionId, invalid HTTPS URL |
| `RESOURCE_NOT_FOUND` | User or event type not found |
| `UNAUTHORIZED` | Not authenticated |
