# ADR: Sync Projection Version Semantics (RFC v3 / Phase 2 Hardening)

## Status
Accepted (v1 single-region scope)

## Decision
`projection_version` guarantees **per-row monotonic acceptance only** for a given
`(connection_id, provider, external_event_id)` projection key.

## Guarantees in v1
- Writers are serialized per row through DB locking (`PESSIMISTIC_WRITE`).
- A successfully applied observation increments `projection_version` by exactly one.
- Stale/equal observations are rejected by comparator + persistence guard.
- Provider metadata (`sequence`, `updated_at`, `etag`, `payload_hash`) is **advisory**
  for freshness; it is not authoritative distributed ordering.

## Non-Guarantees in v1
- No global causal ordering.
- No cross-row monotonic ordering.
- No multi-region ordering guarantees.
- No distributed workflow/policy engine semantics.

## Assumptions
- Single primary region.
- Single authoritative database clock and lock manager.
- Moderate contention per projection key.

## Operational Warnings
- Do not treat `projection_version` as a global event stream sequence.
- Do not infer cross-booking order from projection versions.
- Do not treat provider timestamps/etags as globally monotonic.

## Rollout Note
This ADR intentionally keeps scope constrained to RFC v3 v1 boundaries.
Any move to multi-region/multi-provider ordering requires a new ADR.
