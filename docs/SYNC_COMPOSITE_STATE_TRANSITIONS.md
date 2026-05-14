# Composite State Transition Semantics

## Semantics Source
Transition legality and steady/transient/repair/illegal classifications are explicitly encoded in:
- `src/main/resources/sync/composite_state_matrix.csv`

## Interpretation
- `LEGAL_STEADY`: converged and acceptable for current ownership model.
- `LEGAL_TRANSIENT`: expected temporary in-flight state while sync/reconcile progresses.
- `REPAIR_REQUIRED`: state is recoverable but requires deterministic repair/requeue flow.
- `ILLEGAL_ALERT`: forbidden combination; alert and optional fail-fast in non-production.

## Ownership Model
- Booking lifecycle semantics: `booking-service`
- Sync pipeline transients: `sync-orchestrator` / `sync-worker`
- Repair semantics: `reconcile-engine`
- Illegal-state governance: `sync-invariants`

## Explicit Constraint
No rule-order predicates determine legality. Matrix row is the sole authority.
