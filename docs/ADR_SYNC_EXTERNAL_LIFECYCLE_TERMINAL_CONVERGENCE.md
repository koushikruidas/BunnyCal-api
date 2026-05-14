# ADR: External Lifecycle Semantics and Terminal Convergence (RFC v3)

## Status
Accepted (feature-flagged runtime enforcement)

## Scope
This phase formalizes externally-originated lifecycle semantics and loop suppression for reconcile behavior.

Non-goals:
- No legacy runtime removal.
- No generalized workflow engine.
- No aggressive automation/cutover.
- No multi-provider abstraction expansion.

## External Lifecycle Semantic Matrix

| Scenario | Local lifecycle outcome | Reconcile behavior | Terminal convergence | Operator visibility |
|---|---|---|---|---|
| Organizer external delete (provider event missing) | Local booking remains canonical; no auto-cancel in this phase | Suppress recreate, mark terminal external delete | `TERMINAL_EXTERNAL_DELETE` | `reconcileSuppressed=true`, lifecycle reason persisted |
| Attendee external delete equivalent signal | Treated as external delete signal | Same as above | `TERMINAL_EXTERNAL_DELETE` | Same as above |
| Organizer external cancel | Observation treated as tombstone/missing-equivalent in this phase | Suppress recreate; manual/local decision required | `TERMINAL_EXTERNAL_DELETE` | explicit lifecycle state |
| Attendee decline | No implicit local cancel | no aggressive mutation | `STABLE` or participation drift outside booking terminal change | decision lineage + parity logs |
| Provider missing event while local expects exists | No auto recreate when external lifecycle semantics enabled | `REQUIRE_MANUAL_REVIEW` + suppression marker | `TERMINAL_EXTERNAL_DELETE` | lifecycle + reason persisted |
| Provider calendar disconnect / auth revoked | No local auto-cancel | mark external action required | `EXTERNAL_ACTION_REQUIRED` | lifecycle state + reason surfaced |
| Provider permission loss | No local auto-cancel | mark external action required | `EXTERNAL_ACTION_REQUIRED` | lifecycle state + reason surfaced |

## Terminal Convergence States

Explicit lifecycle states:
- `STABLE`
- `ACTIVE_DRIFT`
- `TERMINAL_EXTERNAL_DELETE`
- `EXTERNAL_ACTION_REQUIRED`
- `PROVIDER_STATE_ORPHANED`

These states are deterministic from persisted/reconstructable reconcile inputs and provider observations.

## Drift Loop Suppression

When terminal semantics are detected (`suppressReconcile=true`):
- Reconciler does not enqueue further repair jobs.
- Job is persisted as `SYNCED` with lifecycle marker in `calendar_sync_jobs.last_error`:
  - `TERMINAL_EXTERNAL_DELETE`
  - `EXTERNAL_ACTION_REQUIRED`
  - `PROVIDER_STATE_ORPHANED`
- Decision lineage remains append-only in `sync_reconcile_decision_log`.

This prevents infinite delete/recreate loop storms while preserving auditability.

## Reconcile Intent Governance

Policy (feature-flagged):
- Recreate provider events: only when not terminal-suppressed.
- Preserve local canonical booking lifecycle by default.
- External cancels/deletes do not auto-cancel local bookings in this phase.
- Provider disconnect/permission failures require external/manual action state.

## Backend Lifecycle Contract (Frontend-facing)

Meeting listing now exposes:
- `externalLifecycleState`
- `externalLifecycleReason`
- `reconcileSuppressed`
- `actionRequired`

These are authoritative backend lifecycle semantics for UI reflection, without frontend orchestration coupling.

## Replay/Convergence Safety

- Anti-resurrection remains preserved: terminal external delete states suppress recreate loops.
- Decision evaluation remains deterministic and side-effect free.
- Suppression markers are monotonic and replay-safe.
- Stale replay cannot re-enable suppressed repairs unless explicit new state transition occurs.

## Rollout and Rollback

Feature flag:
- `sync.reconcile.external-lifecycle.enabled` (default `false`)

Rollout:
1. Enable in non-prod.
2. Validate suppression and lifecycle metrics.
3. Enable in production gradually.

Rollback:
- Disable flag to revert to prior reconcile behavior.
- No schema rollback needed.
