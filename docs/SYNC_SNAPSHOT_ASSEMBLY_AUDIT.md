# Snapshot Assembly Audit Report

## Risks Identified
1. Runtime-inferred reconcile inputs omitted persisted projection_version and terminal_intent_epoch.
2. Projection lookup ambiguity possible when provider/external_event_id maps to multiple rows.
3. Participation lifecycle is still minimally represented (`NEEDS_ACTION`) in this phase.
4. Recurring semantics are partial and rely on hint-level signals.

## Implemented Mitigations
- persisted composite snapshots captured before shadow decision evaluation
- deterministic snapshot hash/version lineage
- runtime-vs-snapshot parity metrics and mismatch counters
- projection ambiguity counters

## Deferred Debt
- full recurring-series semantic reconstruction
- complete persisted participation state ingestion
- authority transition threshold automation

## Explicit Transient Inferred Assumptions (Documented)
- Participation lifecycle currently defaults to `NEEDS_ACTION` in persisted snapshots when authoritative participation ingestion is unavailable.
- This is treated as a constrained v1 inferred field and must not be used for aggressive automation decisions.
- Booking, sync, projection, projection_version, and terminal_intent_epoch are persisted authoritative inputs in this phase.
