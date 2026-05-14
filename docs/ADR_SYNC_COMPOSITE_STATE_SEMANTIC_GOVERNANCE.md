# ADR: Composite State Semantic Governance

## Status
Accepted

## Decision
Composite sync semantics are governed by explicit reviewed artifacts:
- `src/main/resources/sync/composite_state_matrix.csv`
- `src/main/resources/sync/composite_state_rationale_catalog.csv`
- `src/main/resources/sync/semantic_governance.lock`

## Governance Rules
1. Every composite state must map explicitly to one classification:
   - `LEGAL_STEADY`
   - `LEGAL_TRANSIENT`
   - `REPAIR_REQUIRED`
   - `ILLEGAL_ALERT`
2. No fallback/default legality is allowed.
3. Rationale/owner/policy must be reviewed metadata on every row.
4. Matrix and rationale artifacts are hash-locked in CI.
5. Enum cardinality changes require explicit governance lock update.

## Transition Semantics Ownership
- `booking-service`: steady lifecycle ownership for booking semantics.
- `sync-orchestrator`/`sync-worker`: transient operational pipeline semantics.
- `reconcile-engine`: repair-required semantics for convergence safety.
- `sync-invariants`: illegal-state detection authority.

## Non-Goals
- No generalized policy engine.
- No runtime user-defined rules.
- No multi-region semantics.
