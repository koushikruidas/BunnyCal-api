# Phase 3 Provider Normalization and Deterministic Scheduling

## 1. Canonical conferencing architecture
- Added canonical model: `ConferenceDetails { provider, joinUrl, dialIn, meetingCode, password, rawPayload, sourceOfTruth, updatedAt }`.
- Provider-specific conferencing payloads are normalized at the sync boundary (`BookingSyncWorker`) and persisted through canonical metadata JSON.
- Downstream sync metadata now stores canonical conference fields rather than relying on provider-native field names.

## 2. Canonical availability architecture
- Added canonical availability model: `BusyInterval { start, end, sourceProvider, sourceCalendarId, sourceEventId }`.
- `CalendarBusyTimeService` now builds canonical busy intervals first, then maps to `TimeInterval` for existing slot engine compatibility.
- Slot engine input is provider-agnostic and no longer directly coupled to provider-specific busy-event shapes.

## 3. Microsoft ingestion audit results
- Microsoft intervals are now surfaced in canonical interval logs with explicit provider attribution.
- Added ingestion diagnostics logs:
  - `availability_busy_intervals_canonicalized`
  - `microsoft_availability_ingestion_freshness`
- This enables direct operator visibility into whether Microsoft busy data is present and fresh for slot generation windows.

## 4. Duplicate prevention guarantees
- Existing duplicate projection guards remain active in `BookingSyncWorker`:
  - ownership-linked external ID short-circuit on create
  - idempotent provider operation rows in `calendar_provider_operations`
- Added explicit `provider_create_retry_detected` log when idempotent create rows are reused.

## 5. Projection traceability system
- Added structured projection write trace logs in `DefaultCalendarService` for create/update/delete:
  - `projection_write_trace`
- Trace captures booking identity, provider, connection target, lifecycle operation, and external event ID.
- Existing ownership/stale-job logs remain unchanged and continue to enforce deterministic lifecycle authority.

## 6. Recurring ownership readiness report
- Added recurrence-ready ownership columns in `booking_ownership`:
  - `series_external_id`
  - `instance_external_id`
  - `recurrence_id`
- Added migration `V61_0__booking_ownership_recurrence_keys.sql` with supporting indexes.
- No heuristic recurrence linking was introduced; deterministic targeting still relies on `provider_external_event_id`.

## Notes
- This phase does not redesign scheduling, reconciliation, or provider architecture.
- Changes are incremental and keep application-authoritative lifecycle ownership intact.
