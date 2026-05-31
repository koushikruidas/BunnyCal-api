# ADR: Authority Transition Strategy (Foundations Only)

## Status
Accepted (design-only in this phase)

## Objective
Define safe gates for future transition from runtime-inferred reconcile inputs to snapshot-authoritative inputs.

## Gates
- sustained snapshot parity stability
- no replay reconstruction mismatches above threshold
- no invariant mismatch trend
- rollback path validated

## Constraints
- no legacy runtime removal in this phase
- no aggressive reconcile automation in this phase
