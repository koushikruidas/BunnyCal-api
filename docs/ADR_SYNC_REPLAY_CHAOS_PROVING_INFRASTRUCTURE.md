# ADR: Replay and Chaos Proving Infrastructure (RFC v3)

## Status
Accepted

## Scope
This phase is limited to correctness proving under replay/disorder.
No reconcile automation expansion, no legacy path removal, no queue redesign.

## Decisions
1. Deterministic replay harness remains side-effect isolated from production mutation paths.
2. Replay fixtures preserve arrival ordering metadata and delivery identifiers.
3. Replay archive exports redact sensitive payload fields while preserving ordering/version metadata.
4. Convergence assertions are executable and fail on invariant-violation indicators.
5. Chaos scenarios are deterministic via seed-based options and reproducible interleavings.

## Proved Properties
- duplicate delivery collapse is deterministic
- stale observation rejection is deterministic
- stale create-after-cancel anti-resurrection guard is preserved
- projection advancement/no-op accounting is stable
- replay determinism for identical input+seed

## Non-Goals
- no production dashboard/operator workflow additions
- no authority transition
- no multi-provider abstraction
- no multi-region semantics

## Rollout Recommendations
1. Keep replay tooling non-production by convention and CI/test-only invocation.
2. Use captured fixtures from staging-like traffic windows with redaction.
3. Gate semantic changes with replay determinism and convergence assertion tests before rollout.
4. Preserve rollback safety by avoiding mutation-path coupling.
