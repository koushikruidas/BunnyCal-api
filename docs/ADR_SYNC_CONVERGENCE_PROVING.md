# ADR: Convergence Proving Strategy

## Status
Accepted

## Strategy
Convergence proving combines:
1. immutable webhook fixture capture,
2. deterministic replay execution,
3. shadow parity analysis,
4. invariant and anti-resurrection assertions.

## Required Assertions
- no zombie resurrection,
- terminal intent monotonicity,
- projection version monotonicity,
- deterministic terminal digest for identical replay options.

## Non-Goals
- No automatic provider mutation.
- No legacy path removal.
- No projection-authoritative lifecycle ownership in this phase.
