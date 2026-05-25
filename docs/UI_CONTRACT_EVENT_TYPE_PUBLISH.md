# UI Contract: Event Type Publishing

**Applies to:** `POST /api/event-types` · `GET /api/event-types`
**Backend version:** post-convergence (Phase 3)
**Supersedes:** any prior contract referencing `orchestrationProvider`, `calendarProvider`, `organizerCalendarConnectionId`, `conferencingProvider` (top-level), `customConferenceUrl` (top-level), or `authoritativeSchedulingProvider`

---

## Overview

The event-type publishing flow has two prerequisite data sources the UI must fetch before rendering the create/edit form:

1. **Provider catalog** — `GET /api/integrations/providers` — tells the UI which calendar connections and conferencing providers are available for this user
2. **Event type** (on edit) — `GET /api/event-types` — returns the saved shape to pre-populate the form

The form submits to `POST /api/event-types`. There is no `PUT` endpoint yet; re-creation is used for updates.

---

## 1. Prerequisite: Provider Catalog

Fetch once on form mount. Used to populate the calendar picker and conferencing picker.

### `GET /api/integrations/providers`

```json
{
  "version": "v1alpha-provider-catalog",
  "providers": [
    {
      "providerId": "google",
      "providerType": "HYBRID",
      "capabilities": {
        "supportsIdentity": true,
        "supportsAvailability": true,
        "supportsScheduling": true,
        "supportsConferencing": true,
        "supportsOAuth": true,
        "supportsSSO": false,
        "supportsWebhookLifecycle": true,
        "supportsExternalCancellation": true,
        "supportsConferenceProvisioning": false,
        "supportsMultipleCalendars": true,
        "supportsPushRenewal": true
      },
      "lifecycleSourceOfTruth": "WEBHOOK_AND_POLL",
      "status": {
        "connectionStatus": "CONNECTED",
        "isConnected": true,
        "isActionRequired": false
      },
      "roles": {
        "isIdentityProvider": true,
        "isAvailabilityProvider": true,
        "isConferencingProvider": false
      },
      "metadata": {
        "calendarProviderType": "GOOGLE"
      }
    },
    {
      "providerId": "microsoft",
      "providerType": "HYBRID",
      "capabilities": { "...": "..." },
      "status": { "connectionStatus": "NOT_CONNECTED", "isConnected": false, "isActionRequired": false },
      "roles": { "isIdentityProvider": false, "isAvailabilityProvider": false, "isConferencingProvider": false },
      "metadata": { "calendarProviderType": "MICROSOFT" }
    },
    {
      "providerId": "zoom",
      "providerType": "CONFERENCING",
      "capabilities": { "supportsConferencing": true, "supportsOAuth": true, "supportsConferenceProvisioning": true, "...": false },
      "status": { "connectionStatus": "CONNECTED", "isConnected": true, "isActionRequired": false },
      "roles": { "isIdentityProvider": false, "isAvailabilityProvider": false, "isConferencingProvider": true },
      "metadata": { "conferencingProviderType": "ZOOM" }
    },
    {
      "providerId": "google_meet",
      "providerType": "CONFERENCING",
      "status": { "connectionStatus": null, "isConnected": false, "isActionRequired": false },
      "roles": { "isConferencingProvider": false },
      "metadata": { "conferencingProviderType": "GOOGLE_MEET" }
    },
    {
      "providerId": "microsoft_teams",
      "providerType": "CONFERENCING",
      "status": { "connectionStatus": null, "isConnected": false, "isActionRequired": false },
      "roles": { "isConferencingProvider": false },
      "metadata": { "conferencingProviderType": "MICROSOFT_TEAMS" }
    },
    {
      "providerId": "custom_url",
      "providerType": "CONFERENCING",
      "status": { "connectionStatus": null, "isConnected": false, "isActionRequired": false },
      "roles": { "isConferencingProvider": false },
      "metadata": { "conferencingProviderType": "CUSTOM_URL" }
    }
  ],
  "authority": {
    "identityProvider": "google",
    "availabilityProviders": ["google"],
    "lifecycleAuthority": "application",
    "conferencingProviders": ["zoom"]
  }
}
```

**UI usage rules:**

- Show calendar pickers only for providers where `status.isConnected = true` and `providerType` is `HYBRID` or `CALENDAR`.
- Show a conferencing option for a provider unconditionally — availability depends on the type:
  - `zoom` — show only when `status.isConnected = true` (requires OAuth)
  - `google_meet` — always show; no standalone OAuth needed (managed by Google calendar)
  - `microsoft_teams` — always show; no standalone OAuth needed (managed by Microsoft calendar)
  - `custom_url` — always show; user supplies their own URL
- **Do NOT use `authority.availabilityProviders` or `roles.isAvailabilityProvider` to gate conferencing options.** Calendar and conferencing are decoupled — Zoom with Outlook calendars is valid.

---

## 2. Create Event Type

### `POST /api/event-types`

**Authentication:** Bearer token required.

### Request shape

```json
{
  "name": "30 Minute Intro",
  "description": "A quick intro call",
  "location": "Remote",
  "durationMinutes": 30,
  "bufferBeforeMinutes": 5,
  "bufferAfterMinutes": 5,
  "slotIntervalMinutes": 30,
  "minNoticeMinutes": 60,
  "maxAdvanceDays": 60,
  "holdDurationMinutes": 10,
  "slug": "30-min-intro",
  "availabilityCalendars": [
    {
      "connectionId": "uuid-of-calendar-connection",
      "provider": "google",
      "externalCalendarId": "user@gmail.com"
    }
  ],
  "conference": {
    "enabled": true,
    "provider": "zoom",
    "customUrl": null
  }
}
```

### Request field reference

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Non-blank. Max display name for the event type. |
| `description` | string | no | Shown on the public booking page. |
| `location` | string | no | Free-text location (physical address, city, etc.). Not the conference link. |
| `durationMinutes` | integer | yes | > 0. Length of the meeting. |
| `bufferBeforeMinutes` | integer | yes | ≥ 0. Protected time before the slot. |
| `bufferAfterMinutes` | integer | yes | ≥ 0. Protected time after the slot. |
| `slotIntervalMinutes` | integer | yes | > 0. How far apart offered slots are. Typically equal to `durationMinutes`. |
| `minNoticeMinutes` | integer | yes | ≥ 0. Minimum lead time before a booking can be made. |
| `maxAdvanceDays` | integer | yes | ≥ 0. How far ahead bookings are accepted. |
| `holdDurationMinutes` | integer | yes | > 0. How long a pending booking hold is reserved. |
| `slug` | string | no | URL-friendly identifier. Auto-derived from `name` if omitted. Uniqueness enforced per user; a numeric suffix is appended if taken. |
| `availabilityCalendars` | array | no | Calendars to check for busy/free. Empty array = use all connected calendars (backward-compatible mode). |
| `availabilityCalendars[].connectionId` | UUID | yes (per entry) | Must be an active calendar connection owned by the authenticated user. |
| `availabilityCalendars[].provider` | string | yes (per entry) | `"google"` or `"microsoft"`. Must match the connection's actual provider. |
| `availabilityCalendars[].externalCalendarId` | string | no | Provider-side calendar identifier (email address for Google primary, calendar ID for sub-calendars). Omit to use the connection's primary calendar. |
| `conference.enabled` | boolean | yes (if `conference` present) | `false` disables conferencing. |
| `conference.provider` | string | conditional | Required when `enabled: true`. One of: `"zoom"`, `"google_meet"`, `"microsoft_teams"`, `"custom_url"`. Case-insensitive; hyphens and underscores both accepted. Omitting when `enabled: true` defaults to `"google_meet"`. |
| `conference.customUrl` | string | conditional | Required when `provider: "custom_url"`. Must be a valid `https://` URL. Ignored for all other providers. |

**Removed fields — do not send, silently ignored if sent:**
- `organizerCalendarConnectionId`
- `orchestrationProvider`
- `calendarProvider`
- `conferencingProvider` (top-level)
- `customConferenceUrl` (top-level)

### Validation errors (HTTP 400)

| Condition | Error |
|---|---|
| `name` null or blank | `VALIDATION_ERROR` |
| `durationMinutes` ≤ 0 or null | `VALIDATION_ERROR` |
| `slotIntervalMinutes` ≤ 0 or null | `VALIDATION_ERROR` |
| Any buffer/notice/advance/hold value null or out of range | `VALIDATION_ERROR` |
| `availabilityCalendars[].connectionId` not found, not owned, or not active | `VALIDATION_ERROR` |
| `conference.provider = "custom_url"` and `customUrl` missing or not `https://` | `VALIDATION_ERROR` |
| `conference.enabled = true` and `enforceNativeConferenceProviderMatch = true` (non-default flag) and provider mismatch | `VALIDATION_ERROR` |

---

## 3. Response shape

Both `POST /api/event-types` and `GET /api/event-types` return the same envelope.

### POST response (HTTP 200)

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "30 Minute Intro",
    "slug": "30-min-intro",
    "link": "/public/username/30-min-intro",
    "availabilityCalendars": [
      {
        "connectionId": "uuid-of-calendar-connection",
        "provider": "google",
        "externalCalendarId": "user@gmail.com"
      }
    ],
    "conference": {
      "enabled": true,
      "provider": "ZOOM",
      "customUrl": null
    }
  }
}
```

### GET response (HTTP 200) — list

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "name": "30 Minute Intro",
      "slug": "30-min-intro",
      "link": "/public/username/30-min-intro",
      "availabilityCalendars": [...],
      "conference": { "enabled": true, "provider": "ZOOM", "customUrl": null }
    }
  ]
}
```

### Response field reference

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Stable identifier for this event type. |
| `name` | string | Display name as stored. |
| `slug` | string | Final URL slug (may have suffix appended if original was taken). |
| `link` | string | Relative path to the public booking page: `/public/{username}/{slug}`. |
| `availabilityCalendars` | array | Mirrors what was saved. Empty array when no explicit calendars were selected (all-calendars mode). |
| `availabilityCalendars[].connectionId` | UUID | |
| `availabilityCalendars[].provider` | string | Lower-case: `"google"` or `"microsoft"`. |
| `availabilityCalendars[].externalCalendarId` | string \| omitted | Omitted (not null) when not set. |
| `conference.enabled` | boolean | |
| `conference.provider` | string | UPPER_CASE enum name: `"ZOOM"`, `"GOOGLE_MEET"`, `"MICROSOFT_TEAMS"`, `"CUSTOM_URL"`, `"NONE"`. |
| `conference.customUrl` | string \| omitted | Present only when `provider = "CUSTOM_URL"`. Omitted otherwise. |

**Note on `conference.provider` casing:** The response serializes the Java enum name (UPPER_CASE with underscores). The request accepts lower-case, mixed-case, and hyphenated variants. The UI should normalize to lower-case for display and send lower-case in requests.

**Removed fields — no longer present in response:**
- `orchestrationProvider`
- `calendarProvider` (top-level)
- `conferencingProvider` (top-level)
- `customConferenceUrl` (top-level)
- Any `OrchestrationResponse` wrapper object

---

## 4. Calendar picker UX requirements

The `availabilityCalendars` array drives which calendars are checked for free/busy when generating booking slots. The UI must populate it correctly.

### What to show in the picker

Query the provider catalog. For each provider where `status.isConnected = true` and `providerType` is `HYBRID` or `CALENDAR`:

- Offer the primary calendar of that connection (omit `externalCalendarId` or send the primary email)
- If the provider supports `supportsMultipleCalendars = true`, optionally fetch the sub-calendar list from the calendar integration endpoints and offer each as a separate selectable entry

### What to send

Each selected calendar entry must include:
- `connectionId` — the UUID from the provider catalog entry's connection (obtained from `GET /api/integrations/calendar/connections` or stored from when the connection was established)
- `provider` — lower-case string matching the connection's provider: `"google"` or `"microsoft"`
- `externalCalendarId` — the provider-side ID if a specific sub-calendar is selected; omit for the primary

### Empty selection

If the user selects no calendars (or the UI omits `availabilityCalendars`), send an empty array `[]` or omit the field. The backend will fall back to checking all active calendar connections for that user. This is the behavior for all existing event types created before the availability calendar selection feature.

### Mixed-provider selection

The UI may allow selecting calendars from multiple providers simultaneously (e.g., Google primary + Outlook work). This is fully supported. Send all selected entries in the array. The first entry's connection becomes the internal sync connection for calendar writes; order matters only for that implicit selection.

---

## 5. Conferencing picker UX requirements

### Provider display names

| `provider` value (send in request) | Display label |
|---|---|
| `zoom` | Zoom |
| `google_meet` | Google Meet |
| `microsoft_teams` | Microsoft Teams |
| `custom_url` | Custom URL |

### Zoom

**Always reliable.** Zoom is a standalone OAuth provider — the meeting is created directly via Zoom API regardless of which calendar the user has connected. Show this option only when the provider catalog `zoom` entry has `status.isConnected = true`. If not connected, show a "Connect Zoom" CTA instead.

### Custom URL

**Always reliable.** No provider dependency. When selected, show a text input for the URL. Enforce `https://` client-side (the server also validates and returns HTTP 400 if invalid).

### Google Meet

**Provider-dependent.** Google Meet is provisioned as a side effect of Google Calendar event creation. It requires the user's **sync connection** (determined server-side as the oldest active calendar connection) to be a Google connection.

- If the user has a Google connection: Google Meet will work correctly.
- If the user has only a Microsoft connection: selecting Google Meet will result in a booking with **no conference link** (the Meet instruction is silently dropped by the backend).
- If the user has both connections and the Google connection is older: Google Meet works.

**Frontend must surface this constraint:**
- Detect whether the user has a Google calendar connection (`providers` where `providerId = "google"` and `status.isConnected = true`).
- If they do not: disable Google Meet or show a warning ("Requires a Google calendar connection").
- Do not present Google Meet as freely available regardless of the user's connections.

### Microsoft Teams

**Provider-dependent.** Symmetric to Google Meet. Teams is provisioned as a side effect of Microsoft Graph event creation.

- If the user's sync connection is Microsoft: Teams works correctly.
- If the user has only a Google connection: selecting Teams results in a booking with **no conference link**.

**Frontend must surface this constraint:**
- Check whether the user has a Microsoft calendar connection (`providers` where `providerId = "microsoft"` and `status.isConnected = true`).
- If they do not: disable Microsoft Teams or show a warning ("Requires a Microsoft calendar connection").

### Determining the user's sync connection

The sync connection is the oldest active calendar connection for the user, determined server-side. The frontend can approximate this by looking at the provider catalog: the `authority.availabilityProviders` list reflects which providers have active connections. The first HYBRID provider with `status.isConnected = true` in the catalog is a reasonable proxy (not guaranteed to be ordered by creation date, but usable for capability gating).

The safest frontend approach is:
- If the user has ONLY a Google connection → disable Teams, enable Meet
- If the user has ONLY a Microsoft connection → disable Meet, enable Teams
- If the user has BOTH → enable both (the backend will use the oldest connection)
- If neither → disable both native providers

### Disabling conferencing

Send `{ "enabled": false, "provider": null, "customUrl": null }` or omit the `conference` field entirely.

---

## 6. Migration notes for existing UI code

| Old field/pattern | Replacement |
|---|---|
| `organizerCalendarConnectionId` in request | First entry of `availabilityCalendars[].connectionId` |
| `calendarProvider` / `orchestrationProvider` in request | Derived automatically from the connection; do not send |
| `conferencingProvider` (top-level string) in request | `conference.provider` inside the `conference` object |
| `customConferenceUrl` (top-level) in request | `conference.customUrl` inside the `conference` object |
| Checking `authority.authoritativeSchedulingProvider` to gate conferencing | Removed; conferencing is unconditionally decoupled |
| Rendering `orchestrationProvider` from response | Removed from response; no replacement needed |
| Showing conferencing options only for the "authoritative" provider | Do not do this; all conferencing options are always available |

Legacy requests that still include the removed fields are accepted without error (`@JsonIgnoreProperties(ignoreUnknown=true)` is applied). They will simply be ignored.

---

## 7. Error envelope

All errors follow the standard API response envelope:

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "name is required."
  }
}
```

HTTP status codes:
- `200` — success
- `400` — validation error (see section 3)
- `401` — missing or invalid auth token
- `404` — resource not found
