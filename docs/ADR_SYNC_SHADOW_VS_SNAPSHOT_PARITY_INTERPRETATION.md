# ADR: Shadow vs Snapshot Input Parity Interpretation

## Status
Accepted

## Categories
- `EXACT_MATCH`: runtime-inferred and persisted-authoritative inputs identical.
- `SNAPSHOT_ENRICHED`: same decision-core identity with additional persisted fields.
- `MISMATCH`: semantic input discrepancy requiring investigation.
- `RECONSTRUCTION_FAILURE`: snapshot assembly failure.

## Guidance
- `SNAPSHOT_ENRICHED` is expected during transition.
- sustained `MISMATCH` requires blocker-level investigation before authority transition.
