# ADR: Sync Lineage Context Semantics (RFC v3 / Phase 2 Hardening)

## Status
Accepted (v1 observability scope)

## Decision
Calendar sync paths propagate a bounded lineage context for debugging and replay analysis:
- `correlation_id`
- `causation_id`
- `booking_id`
- `external_event_id`
- `projection_version`
- `terminal_intent_epoch`

## Scope and Guarantees
- Lineage fields are propagated as structured log/tracing context.
- High-cardinality lineage identifiers are intentionally excluded from metric tags.
- Lineage is best-effort observability metadata, not correctness authority.

## Non-Guarantees
- No guarantee that MDC/thread-local context survives every async boundary.
- No ordering, deduplication, or reconciliation decision may depend on lineage fields.
- No cross-region trace-causality guarantees.

## Implementation Constraints
- Deterministic correctness remains based on persisted state, comparator outcomes, and intent epoch semantics.
- Invariant classification remains side-effect free; monitor emits operational signals separately.

## Rollout Note
This ADR preserves RFC v3 boundaries:
- single-region assumptions,
- per-row projection monotonicity only,
- no generalized workflow/policy engine semantics.
