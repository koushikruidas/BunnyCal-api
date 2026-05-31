# ADR: Sync Lineage Context Semantics (RFC v3 / Phase 2 Hardening)

## Status
Accepted (v1 observability scope)

## Decision
Calendar sync paths propagate a bounded lineage context for debugging and replay analysis.

Authoritative lineage producer at ingress boundary:
- webhook ingress controller/service for webhook-driven observations
- outbox event processor for booking-originated sync work

Bounded lineage fields:
- `correlation_id`
- `causation_id`
- `booking_id`
- `external_event_id`
- `projection_version`
- `terminal_intent_epoch`

Required fields by boundary:
- ingress required: `correlation_id`
- downstream required (best effort): `causation_id`
- optional/enrichment: `booking_id`, `external_event_id`, `projection_version`, `terminal_intent_epoch`

## Scope and Guarantees
- Lineage fields are propagated as structured log/tracing context.
- High-cardinality lineage identifiers are intentionally excluded from metric tags.
- Lineage is best-effort observability metadata, not correctness authority.
- Lineage fields are immutable once set for a processing scope; enrich-only allowed for optional fields.

## Non-Guarantees
- No guarantee that MDC/thread-local context survives every async boundary.
- Async workers may start with partial lineage; missing fields must not change correctness behavior.
- No ordering, deduplication, or reconciliation decision may depend on lineage fields.
- No cross-region trace-causality guarantees.

## MDC Limitations and Fallback
- MDC is thread-local and operationally best-effort.
- For async boundaries, persisted records (`sync_reconcile_input_snapshots`, decision logs, replay fixtures) are the durable fallback lineage source.
- Any missing MDC field must degrade to empty/unknown and never block processing.

## Implementation Constraints
- Deterministic correctness remains based on persisted state, comparator outcomes, and intent epoch semantics.
- Invariant classification remains side-effect free; monitor emits operational signals separately.

## Rollout Note
This ADR preserves RFC v3 boundaries:
- single-region assumptions,
- per-row projection monotonicity only,
- no generalized workflow/policy engine semantics.
