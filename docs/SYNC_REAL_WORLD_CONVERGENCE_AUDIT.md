# Calendar Sync Operational Audit: Real-World Convergence Phase

## Summary
This audit focuses on convergence behavior under provider disorder, replay determinism, and shadow safety.

## What Is Strong
- Projection monotonic acceptance and tombstone guard are in place.
- Deterministic shadow reconcile decision logging exists.
- Canonical input hash contract and parity taxonomy are documented.
- Invariant matrix governance remains explicit and validated.

## Risks Found
1. Provider-disorder realism gap:
- Existing replay harness was model-level and not fed from captured production-shaped webhook payloads.

2. Recurring-event blind spot:
- Recurring events were not separately measured in shadow divergence reporting.

3. Fixture lineage gap:
- Raw webhook deliveries were not captured as immutable replay fixtures with arrival ordering metadata.

4. Persisted snapshot completeness (deferred debt):
- Reconcile input still omits full persisted composite snapshot assembly across all domains.

5. Wall-clock coupling boundary:
- Operational latency metrics use wall clock; correctness semantics remain deterministic but this boundary must stay explicit.

## Scope-Constrained Actions In This Phase
- Added immutable webhook replay fixture capture.
- Added deterministic replay engine for captured fixture sequences.
- Added recurring divergence observability in shadow parity path.
- Added docs for replay fixture format, provider assumptions, recurring limitations, and escalation guidance.

## Deferred-but-Important Debt
- Full persisted composite snapshot assembly for reconcile input.
- Production-history replay at scale with long-window provider archives.
- Recurring-series semantic normalization beyond lightweight recurring hints.
