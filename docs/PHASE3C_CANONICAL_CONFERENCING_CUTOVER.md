# Phase 3C — Canonical Conferencing Cutover & Legacy Contract Removal

## Objective

Remove all provider-shaped conferencing leakage from API contracts, DTOs, and downstream systems.
`ConferenceDetails` / `ConferenceDetailsResponse` is now the **only** conferencing shape beyond the adapter boundary.

---

## 1. Removed Legacy Conferencing Contracts

### DTOs (API surface)

| File | Change |
|------|--------|
| `booking/dto/MeetingSummaryResponse` | Removed `String conferenceUrl` field and compatibility constructor |
| `booking/dto/PublicManageBookingResponse` | Removed `String conferenceUrl` field and compatibility constructor |

Both records now expose **only** `ConferenceDetailsResponse conferenceDetails`.

The compatibility constructors in Phase 3B bridged from a raw `conferenceUrl` String — those shims are gone. Any caller that relied on them now fails at compile time (fail-fast design intent).

### Services

| File | Change |
|------|--------|
| `booking/service/MeetingQueryService` | Builds `ConferenceDetailsResponse` inline from `MeetingRow.getConferenceUrl()` rather than passing raw URL through DTO |
| `booking/service/PublicBookingService` | Same — constructs canonical response object before passing to `PublicManageBookingResponse` |

The `MeetingRow` interface retains `getConferenceUrl()` as an internal projection detail (the SQL COALESCE across `calendar_sync_jobs`, `calendar_event_mappings`, `conferencing_event_mappings` is unchanged). This is correct: the DB column is an implementation detail of the persistence layer, not an API contract.

---

## 2. Final Canonical Conferencing Architecture

```
Provider Clients (Google, Microsoft)
  │  native fields: hangoutLink, joinWebUrl, conferenceData
  ▼
Conferencing Adapter Layer  [ADAPTER BOUNDARY]
  │  ConferencingInstruction (normalized)
  │  ConferenceDetails (canonical record)
  ▼
Sync Worker / Orchestrator
  │  ConferenceDetails stored as conference_metadata_json
  ▼
Booking Repository (SQL projection)
  │  COALESCE(csj.conference_url, cem.conference_url, conf.join_url) AS conferenceUrl
  ▼
MeetingQueryService / PublicBookingService
  │  ConferenceDetailsResponse built inline
  ▼
API DTOs  (MeetingSummaryResponse, PublicManageBookingResponse)
  │  conferenceDetails: ConferenceDetailsResponse only
  ▼
Frontend / API consumers
```

### ConferenceDetailsResponse fields
```java
record ConferenceDetailsResponse(
    String provider,    // e.g. "GOOGLE_MEET", "MICROSOFT_TEAMS", "ZOOM", "NONE"
    String joinUrl,
    String dialIn,
    String meetingCode,
    String password,
    String sourceOfTruth  // "projection", "conferencing_coordinator", etc.
)
```

---

## 3. Adapter Boundary Enforcement Rules

**Allowed inside adapter packages only:**
- `hangoutLink` — Google Calendar response field
- `joinWebUrl` — Microsoft Teams response field  
- `conferenceData` — Google Calendar request/response structure
- `GOOGLE_MEET` / `MICROSOFT_TEAMS` switch branches for request construction

**Adapter packages** (provider-native handling permitted):
- `io.bunnycal.calendar.client.*`
- `io.bunnycal.calendar.provider.*`
- `io.bunnycal.calendar.replay.*`

**Forbidden outside adapter boundary:**
- Any reference to `hangoutLink`, `joinWebUrl`, `conferenceData`
- Provider-specific branching (`if provider == GOOGLE_MEET`) in notifications, templates, DTOs, SlotService, ICS rendering, projection services

---

## 4. Removed Provider-Shaped Payload Surfaces

| Surface | Before 3C | After 3C |
|---------|-----------|----------|
| `MeetingSummaryResponse` | `conferenceUrl: String` + `conferenceDetails` dual fields | `conferenceDetails` only |
| `PublicManageBookingResponse` | `conferenceUrl: String` + `conferenceDetails` dual fields | `conferenceDetails` only |
| Compatibility constructors | `new MeetingSummaryResponse(..., conferenceUrl, ...)` | Removed — compile error on old callers |
| `ConferenceDetailsResponse` with `"UNKNOWN"` provider | Built in DTO itself | Built in service layer with provider from row |

---

## 5. Architectural Enforcement Tests

`ConferencingBoundaryArchitectureTest` (4 tests) enforces at build time:

1. **`dtoPayloads_doNotExposeRawConferenceUrl`** — asserts `MeetingSummaryResponse` and `PublicManageBookingResponse` contain no `String conferenceUrl` field
2. **`forbiddenProviderFields_doNotLeakBeyondAdapterBoundary`** — scans all files under `booking/`, `availability/`, `sync/` for `hangoutLink`, `joinWebUrl`, `conferenceData`
3. **`notifications_doNotBranchOnConferencingProvider`** — asserts `BookingNotificationService` and `IcsInviteGenerator` contain no `GOOGLE_MEET`, `MICROSOFT_TEAMS`, `hangoutLink`, `joinWebUrl` references
4. **`conferencingApiDtos_exposeOnlyCanonicalShape`** — asserts `ConferenceDetailsResponse` contains no provider-native field names

---

## 6. Remaining Deferred Areas

- **Recurrence workflows** — no conferencing contract changes needed; deferred to Phase 4
- **`calendar_sync_jobs.conference_url` DB column** — retained as internal persistence; not exposed via API
- **`calendar_event_mappings.conference_url` DB column** — retained as internal persistence; not exposed via API
- **`ConferencingOrchestrator.updateConferenceUrl()`** — writes to `calendar_event_mappings.conference_url` as internal state, not an API shape; within scope

---

## Success Criteria — Status

| Criterion | Status |
|-----------|--------|
| `ConferenceDetails` is the only conferencing contract downstream | ✅ |
| All legacy `conferenceUrl` API fields removed from DTOs | ✅ |
| Provider-specific conferencing logic terminates at adapter boundary | ✅ |
| Old consumers fail at compile time (not silently degrade) | ✅ |
| No provider-shaped conferencing payloads remain in downstream packages | ✅ |
| Architecture tests enforce boundary at build time | ✅ |
| All 1109 tests pass | ✅ |
