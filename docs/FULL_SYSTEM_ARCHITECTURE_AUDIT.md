# Full System Architecture Audit — Scheduling Platform

Date: 2026-05-26  
Scope: Current-state architecture audit (not redesign implementation)  
Objective: Identify responsibility leakage and define a clean long-term architecture path where the application is the only lifecycle authority.

---

## 1. Executive Summary

The platform is operating as a hybrid model with strong foundations:

- Pull scheduler provides correctness/reconciliation.
- Webhooks accelerate freshness.
- Outbox + sync jobs provide deterministic write orchestration to providers.

However, ownership boundaries are still partially blurred in three places:

- calendar projection destination selection (fallback-based organizer connection)
- conferencing metadata normalization/rendering across notifications + provider payloads
- dual-authority surfaces where provider invitation semantics can still leak

Most severe architecture risks:

1. Organizer/projection authority leakage via fallback connection resolution.
2. Inconsistent conference URL canonicalization and lifecycle-aware rendering.
3. ICS/provider organizer semantics mismatch risk (especially Outlook cancellation behavior).
4. Non-strict separation of availability calendars vs projection calendar selection UX semantics.

---

## 2. Current Architecture (As Implemented)

### 2.1 High-level component map

```mermaid
flowchart LR
    UI[UI / Public Booking APIs] --> BS[BookingService]
    BS --> OB[OutboxPublisher]
    OB --> OD[LoggingOutboxEventDispatcher]

    OD --> NOTIF[BookingNotificationService\n(Email + ICS)]
    OD --> JOBS[(calendar_sync_jobs)]

    JOBS --> W[BookingSyncWorker]
    W --> CONF[ConferencingCoordinator]
    W --> CAL[CalendarService]

    CAL --> G[Google API Adapter]
    CAL --> M[Microsoft Graph Adapter]

    G --> GCAL[Google Calendar]
    M --> MCAL[Microsoft Calendar]

    GCAL --> INJ[Incremental/Webhook Ingestion]
    MCAL --> INJ
    INJ --> PROJ[ProviderEventProjectionService]
    PROJ --> CONV[External Convergence Services]

    AVREQ[Slot Request] --> SLOT[SlotService]
    SLOT --> BUSY[CalendarBusyTimeService]
    BUSY --> CE[(calendar_events)]
```

### 2.2 Availability pipeline (current)

- Entry: `SlotController` -> `SlotService`.
- `SlotService` merges:
  - availability rules/overrides
  - active bookings
  - calendar busy intervals
- Busy intervals come from `CalendarBusyTimeService`:
  - `SELECTED` mode: only explicit `availabilityBindings`
  - default/legacy mode: all active connections

Evidence:

- `SlotService` (`availabilityMode == SELECTED` gates explicit bindings).
- `CalendarBusyTimeService` normalizes intervals canonically via `IntervalUtils.normalize`.

### 2.3 Event type orchestration model (current)

`EventTypeOrchestrationNormalizer` currently resolves:

- `availabilityBindings`: explicit free/busy sources
- `conferencing`: event type conference configuration
- `syncConnectionId`: **oldest active connection fallback**

This means projection destination (organizer calendar connection) is selected independently from availability calendars and currently depends on account connection ordering unless explicitly stamped.

### 2.4 Projection/write pipeline (current)

- Booking lifecycle event -> outbox event
- `LoggingOutboxEventDispatcher`:
  - sends notifications
  - upserts sync job (`CREATE` / `UPDATE` / `DELETE`)
  - resolves scheduling connection priority:
    1. event-type organizer connection (if stamped)
    2. oldest active connection fallback
- `BookingSyncWorker` claims/executes jobs and calls `CalendarService`.

### 2.5 Conferencing pipeline (current)

- `ConferencingCoordinator` executes before provider write:
  - `NONE` / `CUSTOM_URL`
  - native provider meet request (`GOOGLE_MEET`, `MICROSOFT_TEAMS`)
  - external provider lifecycle (`ZOOM`) with mapping persistence
- `ConferencingInstruction` is passed into calendar write adapter.

### 2.6 Notification + ICS pipeline (current)

- `BookingNotificationService` handles outbox lifecycle mails and ICS.
- `IcsInviteGenerator` builds REQUEST/CANCEL payloads using stable UID format:
  - `UID: booking-{bookingId}@{uidDomain}`
- MIME structure is intentionally Outlook/Gmail-compatible (`multipart/alternative` + `text/calendar` + attachment).

---

## 3. Authority & Ownership Matrix (Current)

| Concern | Current Owner | Leakage Risk |
|---|---|---|
| Booking lifecycle state | Application | Low |
| Sync enqueue/retry/backoff | Application | Low |
| Availability computation | Application | Low |
| Projection destination selection | Application, but fallback-based | **High** |
| Invitation/cancellation emails | Application intended | **Medium** (provider side-effects possible) |
| Conference URL source of truth | Mixed (mapping, sync metadata, provider payload) | **High** |
| Attendee organizer semantics | Mixed between app ICS + provider event attendees | **Medium/High** |

---

## 4. Root-Cause Analysis for Reported Problems

## A) Conferencing URL missing/inconsistent

Current behavior indicates multiple conference URL sources with non-unified precedence:

- conferencing mapping (`conferencing_event_mappings.join_url`)
- sync job metadata (`conference_url`)
- provider-native event response payload
- provider-specific event fields (`hangoutLink`, Graph `onlineMeeting.joinUrl`)

`BookingNotificationService.resolveConferenceJoinUrl` tries:

1. prepare instruction (non-terminal events)  
2. fallback to persisted mapping

Gaps:

- no single canonical `ConferenceDetails` projection consumed consistently by notifications, templates, and ICS rendering
- terminal event path correctly avoids minting new conference, but may still face stale/missing mapping

## B) Outlook cancellation not auto-removing calendar entry

Likely compatibility risk areas (despite substantial ICS correctness already present):

- organizer identity in ICS may not match organizer identity in provider-originated event copies
- attendee list/roles may differ from provider-side meeting copy
- sequence/version drift between provider event and ICS stream
- dual source of truth (provider event lifecycle + app ICS lifecycle)

Observed positives in code:

- REQUEST/CANCEL methods are explicit
- UID is stable by booking ID
- SEQUENCE increments from booking calendar sequence
- STATUS is set to `CANCELLED` for CANCEL

Likely root issue is not raw MIME construction but organizer/ownership parity across systems.

## C) Provider authority collision (duplicate lifecycle emails)

Google adapter explicitly uses `sendUpdates=none`; Microsoft payload sets `responseRequested=false`.

That is correct intent, but authority collision can still happen if:

- provider-created events still surface attendee-invite semantics in some clients
- native meetings (Meet/Teams) add provider-specific lifecycle behavior
- organizer mailbox rules or calendar-level defaults send downstream notifications

Architecture issue: authority model is implemented in adapters but not yet formalized end-to-end as a strict contract with test assertions per provider.

## D) Cancellation template still showing join action text

Current plain-text template already suppresses join URL for cancelled/terminated events (`body(...)` guard).

If "Join with Google Meet" still appears, likely paths are:

- HTML template path outside this service still reuses confirmation rendering
- UI/template layer reading conference provider label without lifecycle gating
- stale template artifact in a separate notification channel

## E) Microsoft slot generation inconsistency

Availability engine is provider-agnostic after normalization, so root cause is likely upstream ingestion/normalization, not slot algorithm itself.

Probable high-signal failure points:

- Graph free/busy acquisition (`getSchedule`) response shape/timezone edge handling
- connection-specific busy ingestion freshness gaps
- connection status/token failures masked by pull fallback from other sources
- calendar selection mode mismatch (SELECTED vs ALL_CONNECTED)

Need production telemetry segmentation by provider and connection health to confirm where staleness enters.

## F) Duplicate Google calendar events

Most likely duplication classes:

1. App creates event, then provider-side behavior creates attendee-side mirror interpreted as host-side duplicate view.
2. Create path retried with unresolved external ID persistence race.
3. Projection destination ambiguity (fallback oldest active connection vs expected calendar).
4. Native conferencing lifecycle side-effects interpreted as second event in host UI.

`calendar_sync_jobs` uniqueness on `(internal_ref_type, internal_ref_id, provider)` reduces duplicate job creation, but not all cross-calendar or provider-side fanout duplication classes.

---

## 5. Current Flow Trace (End-to-End)

## 5.1 Event type creation

1. `EventTypeController` -> orchestration normalization.
2. Availability bindings normalized from request.
3. Conferencing provider normalized.
4. Organizer/sync connection resolved (currently oldest active fallback if needed).

## 5.2 Slot generation

1. `PublicBookingController` / `SlotController` -> `SlotService.getSlots`.
2. Load event type + rules/overrides + bookings.
3. Resolve calendar busy intervals via `CalendarBusyTimeService`.
4. Run canonical `SlotGenerationEngine`.

## 5.3 Booking confirmation

1. Booking confirmation emits outbox event.
2. `LoggingOutboxEventDispatcher`:
   - send notifications/ICS
   - enqueue sync `CREATE`
3. `BookingSyncWorker.processCreate`:
   - prepares conferencing instruction
   - writes provider event via `CalendarService`
   - persists external event metadata

## 5.4 Reschedule/update

1. Booking update outbox event -> sync `UPDATE`.
2. Worker updates conferencing instruction.
3. Provider event patched/updated.
4. Notification sends updated ICS.

## 5.5 Cancellation

1. Booking cancellation outbox event -> sync `DELETE`.
2. Conferencing canceled where applicable.
3. Provider delete executed (idempotent missing handling present).
4. ICS CANCEL emailed.
5. Provider inbound tombstones converge via projection service.

---

## 6. Identified Architectural Flaws

1. Projection destination semantics are deterministic but not explicit business-owned; fallback connection ordering is operationally brittle.
2. Conferencing abstraction exists operationally but not fully canonicalized as a read model consumed by all renderers/channels.
3. Lifecycle authority is intended to be app-owned but not enforced by a formal contract-test matrix across Google/Microsoft/Zoom scenarios.
4. Availability and projection concerns are conceptually separated, but event-type/user UX semantics can still let them be mentally conflated.
5. Provider-event linkage and ownership traceability still rely on mixed evidence surfaces (sync jobs, mapping rows, projections).

---

## 7. Target Architecture (Long-term, consistent with your intended model)

## 7.1 Hard boundaries

1. Application = sole lifecycle authority for invite/update/cancel semantics.
2. Availability calendars = read-only free/busy sources.
3. Projection calendar = explicit, user-selected write destination.
4. Conferencing = independent provider selection, normalized to canonical object.

## 7.2 Canonical conferencing abstraction

Adopt and propagate a strict canonical object at adapter boundary:

```text
ConferenceDetails {
  provider,
  joinUrl,
  dialIn,
  meetingCode,
  password,
  rawPayload,
  sourceOfTruth,
  updatedAt
}
```

Rules:

- provider-specific fields terminate in provider adapters
- templates, ICS, API responses, and UI consume only canonical model
- lifecycle-aware render policy: cancellations never render join CTA

## 7.3 Canonical calendar ownership model

Per booking, persist immutable ownership tuple:

```text
BookingOwnership {
  bookingId,
  organizerAuthority = APP,
  projectionProvider,
  projectionConnectionId,
  projectionCalendarId,
  providerExternalEventId,
  ownershipVersion
}
```

This removes ambiguity in duplicate investigation and tombstone linkage.

---

## 8. Implementation Roadmap (Ordered)

## Phase 1 — Authority isolation (highest risk)

1. Enforce explicit projection calendar selection at event-type level (remove implicit oldest-active fallback for new configs).
2. Add contract checks ensuring provider invitation auto-send flags remain disabled in all write paths.
3. Add provider lifecycle authority audit logs per create/update/delete.

## Phase 2 — ICS and cancellation correctness

1. Build cross-client compatibility test matrix (Outlook desktop/web, Gmail, Apple Calendar).
2. Validate UID/SEQUENCE/ORGANIZER/ATTENDEE continuity across confirm-update-cancel triplets.
3. Add regression fixtures comparing app ICS vs provider organizer expectations.

## Phase 3 — Availability correctness (Microsoft priority)

1. Add provider-segmented busy ingestion telemetry (fetch success, interval count, staleness age).
2. Add normalization assertions for Graph dateTime/timeZone conversions.
3. Add slot-debug trace mode (request-scoped) showing merged busy contributors.

## Phase 4 — Projection dedup and ownership traceability

1. Persist explicit ownership tuple and expose in diagnostics.
2. Add duplicate-write guard metrics by `(bookingId, provider, projectionCalendarId)`.
3. Add reconciliation rule to detect multi-calendar mirror drift for same booking.

## Phase 5 — Conferencing normalization

1. Materialize canonical `ConferenceDetails` projection.
2. Migrate notification/template/ICS rendering to canonical source.
3. Decommission direct provider-field reads outside adapter/mapping boundary.

## Phase 6 — Template cleanup

1. Ensure cancellation templates (all channels) are lifecycle-aware and conference-CTA suppressed.
2. Add rendering tests per lifecycle state and provider type.

---

## 9. Recurring Event Handling Analysis

Current system appears booking-instance-centric; recurring/detached semantics are not a first-class ownership contract yet.

Risks:

- detached instance mapping ambiguity
- provider recurrence edits that mutate series/instance identity
- cancel/update on instance vs master event mismatch in outbound ICS and inbound linkage

Recommendation:

- introduce explicit recurrence ownership keys (`seriesExternalId`, `instanceExternalId`, `recurrenceId`) in ownership persistence and linkage queries.

---

## 10. Migration / Backfill Implications

1. Existing event types without explicit projection connection need controlled backfill strategy.
2. Existing bookings need ownership tuple backfill from sync job + mapping + provider projection data.
3. Conference canonical model needs one-time data migration from existing mapping and sync metadata.

Backfill safety rules:

- no heuristic ownership guessing when multiple candidates exist
- mark ambiguous rows as `UNRESOLVED_OWNERSHIP` for manual or deterministic follow-up

---

## 11. Remaining Ambiguity Risks

1. Historical bookings created before current mapping contracts may remain partially untraceable.
2. Provider-side mailbox/calendar policies can still create perceived lifecycle side-effects.
3. Native meet providers may evolve payload semantics; adapter contract tests must pin behavior.
4. Multi-connection users without explicit projection configuration remain a core ambiguity class.

---

## 12. Immediate Validation Checklist

1. Verify each active event type has explicit projection connection/calendar.
2. Confirm provider write flags in runtime diagnostics (`sendUpdates=none`, Graph response suppression semantics).
3. Run confirm->update->cancel lifecycle against Outlook and validate auto-removal behavior.
4. Trace Microsoft availability from Graph fetch to slot output for a known busy interval test case.
5. Reproduce duplicate Google event case with full correlation IDs across outbox, sync job, provider response, and projection ingestion.

---

## Appendix: Primary Evidence Files

- `src/main/java/io/bunnycal/availability/service/EventTypeOrchestrationNormalizer.java`
- `src/main/java/io/bunnycal/availability/service/SlotService.java`
- `src/main/java/io/bunnycal/calendar/service/CalendarBusyTimeService.java`
- `src/main/java/io/bunnycal/booking/outbox/LoggingOutboxEventDispatcher.java`
- `src/main/java/io/bunnycal/sync/worker/BookingSyncWorker.java`
- `src/main/java/io/bunnycal/calendar/service/ProviderEventProjectionService.java`
- `src/main/java/io/bunnycal/booking/notification/BookingNotificationService.java`
- `src/main/java/io/bunnycal/booking/notification/IcsInviteGenerator.java`
- `src/main/java/io/bunnycal/calendar/client/HttpGoogleApiClient.java`
- `src/main/java/io/bunnycal/calendar/client/HttpMicrosoftApiClient.java`

