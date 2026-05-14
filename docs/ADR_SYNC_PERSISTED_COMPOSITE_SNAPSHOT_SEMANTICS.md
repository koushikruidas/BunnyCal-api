# ADR: Persisted Composite Reconcile Snapshot Semantics

## Status
Accepted

## Decision
Reconcile evaluation now persists an immutable composite input snapshot before shadow decision evaluation.

## Snapshot Contents
- booking lifecycle state
- sync lifecycle state
- projection lifecycle state
- participation lifecycle state
- invariant classification
- desired action
- observed provider status/error
- projection_version
- terminal_intent_epoch
- provider observation lineage fields
- correlation/causation lineage

## Guarantees
- Snapshot record is append-only and replay-reconstructable.
- Snapshot hash is deterministic and versioned.
- Shadow decisions can be re-evaluated from persisted snapshots.

## Non-Guarantees
- Snapshot does not yet encode full recurring-series normalization.
- Legacy reconcile path remains authoritative in this phase.
