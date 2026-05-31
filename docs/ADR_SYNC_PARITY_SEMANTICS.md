# ADR: Shadow Parity Semantics and Governance (RFC v3 Phase)

## Status
Accepted (shadow rollout governance)

## Purpose
Define operational meaning of shadow parity categories used by `sync.shadow.parity.total`.

## Categories
- `EXACT_MATCH`: Legacy and shadow decisions are identical.
- `ACCEPTABLE_STRICTER`: Shadow is stricter but operationally acceptable for convergence safety.
- `LEGACY_PERMISSIVE`: Legacy is less strict than shadow in a way that may hide drift risk.
- `SAFETY_IMPROVEMENT`: Shadow identifies additional protective action compared to legacy noop.
- `OPERATIONAL_DIVERGENCE`: Decision mismatch not covered by accepted policy equivalence.

## Governance
- Taxonomy changes require ADR update and rollout review.
- Dashboards and alert thresholds must be versioned with taxonomy changes.
- `OPERATIONAL_DIVERGENCE` and sustained `LEGACY_PERMISSIVE` rates require manual investigation.

## Non-Guarantees
- Parity taxonomy is policy interpretation, not direct correctness proof.
- Equal parity category does not imply equal downstream user impact in all contexts.
