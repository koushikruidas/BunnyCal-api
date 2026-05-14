# Calendar Sync RFC v3: Incremental Observation Audit

## Architecture Assessment

### Current strengths
- Webhook ingress already routes into dedup, replay capture, and projection-governed ingestion.
- Ingestion path already enforces projection monotonicity and invariant checks.
- Source attribution (`WEBHOOK`, `PULL_SYNC`, `USER_ACTION`) and lineage context are already available.

### Risks discovered
- `ExternalCalendarSyncClient` was effectively a noop, so webhook misses could not be authoritatively recovered.
- No persisted provider cursor, so incremental replay windows were not reconstructable.
- No cursor CAS/validation, so concurrent scheduler/webhook sync could race cursor advancement.
- Sync-token invalidation (`410`) had no persistent invalidation marker.

### Minimal-safe rollout strategy
- Feature flag gate:
  - `calendar.sync.incremental.enabled=false` by default.
  - `calendar.sync.incremental.shadow-mode=true` default for non-authoritative warmup.
- Keep webhook ingestion path intact and authoritative for observation ingestion trigger.
- Keep full resync fallback on cursor invalidation and missing cursor.

### Backward compatibility concerns
- Existing webhook and scheduler orchestration remains in place.
- Legacy runtime paths are not removed.
- Incremental client is additive and conditional by provider mode.

### Idempotency guarantees required
- Duplicate webhook deliveries must remain collapsed before observation application.
- Projection comparator + projection lock remains correctness authority for state acceptance.
- Cursor advancement uses expected-cursor CAS semantics to prevent stale overwrites.

## Implemented in this phase

- Added persisted cursor fields on `calendar_connections`:
  - `provider_sync_cursor`
  - `provider_cursor_updated_at`
  - `provider_cursor_invalidated_at`
- Added Google incremental observation client:
  - full window observation
  - incremental window observation by sync cursor
  - `410` sync-token invalidation handling
  - deterministic mapping to existing ingestion event model
- Routed scheduler/webhook/oauth sync flows through `SyncBatch` with cursor awareness.
- Added cursor CAS advancement and explicit invalidation via `CalendarConnectionWriteService`.

## Telemetry added

- `calendar.sync.webhook_gap_suspected.total`
- `calendar.sync.incremental_recovery.total`
- `calendar.sync.cursor_invalidated.total`
- `calendar.sync.cursor_conflict.total`
- `calendar.sync.provider_drift_detected.total`
- `calendar.sync.replay_recovery_action.total`
- `calendar.sync.incremental.shadow.total`
- `calendar.sync.incremental.disabled.total`

## Convergence safety analysis

- Incremental/fallback observations still flow through the same projection/invariant pipeline.
- Cursor invalidation converges to full recovery without bypassing ingestion governance.
- Cursor CAS prevents stale concurrent writers from regressing cursor state.
- Shadow mode allows provider observation validation without expanding authority scope.

## Provider-specific edge cases (Google)

- `410 Gone` for expired/invalid `syncToken` is treated as hard invalidation and triggers full resync.
- Deleted/cancelled events may omit complete timing fields; ingestion mapping preserves deterministic placeholder fallback.
- Out-of-order and duplicate windows are expected; projection comparator remains acceptance guard.
- Recurring-instance semantic fidelity remains bounded by current event-shape ingestion model (documented non-guarantee).
