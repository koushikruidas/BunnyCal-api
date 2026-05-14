# ADR: Deterministic Reconcile Decisions (RFC v3 Phase)

## Status
Accepted (shadow-decision substrate)

## Decision Model
A side-effect-free evaluator consumes a persisted-style snapshot and returns one of:
- `NO_ACTION`
- `IGNORE_STALE`
- `REQUIRE_REPAIR`
- `REQUIRE_RESYNC`
- `REQUIRE_MANUAL_REVIEW`

## Required Properties
- Deterministic for identical input.
- Replay-safe (no wall-clock/network dependence).
- No external network calls inside evaluation.

## Persistence
Decision outputs are recorded append-only in `sync_reconcile_decision_log` with:
- input hash
- decision
- rationale code/detail
- observed status/error
- sync job desired-action/status snapshot
- lineage ids when available

## Canonical Input Hash Contract
- Input hashing uses versioned canonical serialization (`schemaVersion` + stable field registry).
- Hashing is computed from canonical JSON, not ad-hoc delimiter-joined strings.
- Any canonical schema evolution must increment schema version to preserve replay audit semantics.

## Constraints
- Decision layer remains advisory/shadow in this phase.
- Existing reconcile mutation flow remains authoritative action executor.
- No aggressive mutation automation introduced.
