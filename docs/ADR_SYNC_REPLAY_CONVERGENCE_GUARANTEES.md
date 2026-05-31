# ADR: Sync Replay and Convergence Guarantees (RFC v3 Phase)

## Status
Accepted (shadow-validation scope)

## Guarantees
- Replay evaluation is deterministic for identical persisted snapshots and identical input ordering.
- Convergence proving uses seeded, reproducible simulation runs.
- Duplicate delivery correctness is enforced by projection/version/intention semantics, not by dedup alone.
- Anti-resurrection intent remains local-canonical: terminal local cancel intent cannot be overwritten by stale external observations.

## Non-Guarantees
- No global ordering guarantee across bookings.
- No multi-region causal ordering guarantee.
- No provider-perfect monotonic metadata guarantee.

## Assumptions
- Single region, single primary DB lock authority.
- Per-projection-row write serialization through DB locking.
- Ambiguous observation acceptance is feature-flagged and observable.

## Shadow Limitations
- Shadow reconcile decisions do not automatically mutate local booking state.
- Shadow parity mismatch is observability-only in this phase.
- Replay harness is model-level and seeded; it is not yet a production-history replayer for provider-native disorder patterns.
- Persisted composite snapshot assembly is not yet fully authoritative across all domains in this phase.

## Rollback Contract
- Shadow decision logging/metrics can be disabled without affecting existing sync mutation paths.
