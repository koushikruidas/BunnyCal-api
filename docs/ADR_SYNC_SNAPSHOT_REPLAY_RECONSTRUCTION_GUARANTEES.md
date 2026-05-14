# ADR: Snapshot Replay Reconstruction Guarantees

## Status
Accepted

## Guarantees
- Historical reconcile inputs can be reconstructed from persisted snapshots.
- Replay reconstruction does not depend on wall-clock or scheduler timing.
- Deterministic hash allows replay equivalence checks.

## Limits
- Reconstruction quality depends on availability of provider projection lineage.
- Recurring events remain partially represented by hints in this phase.
