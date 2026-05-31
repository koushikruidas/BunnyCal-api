# ADR: Shadow Divergence Interpretation

## Status
Accepted

## Interpretation Model
Shadow divergence is interpreted through parity taxonomy and rationale code.

## Review Inputs
- parity category (`sync.shadow.parity.total`)
- decision rationale code
- reconcile decision log lineage
- webhook replay fixture lineage (when available)

## Operational Expectations
- `EXACT_MATCH`: baseline expected behavior.
- `SAFETY_IMPROVEMENT`: investigate as potential correctness improvement.
- `LEGACY_PERMISSIVE`: investigate for latent risk in legacy behavior.
- `OPERATIONAL_DIVERGENCE`: high-priority review.

## Constraint
Shadow divergence remains advisory in this phase; legacy path remains authoritative.
