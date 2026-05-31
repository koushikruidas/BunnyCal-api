# Replay Retention, Schema Evolution, and Escalation

## Retention Guidance
- Keep replay fixtures long enough to cover incident investigation windows.
- Preserve append-only lineage and avoid in-place mutation of fixture records.

## Schema Evolution
- Evolve fixture schema additively when possible.
- Version changes impacting replay semantics must be documented via ADR.

## Escalation Guidance
Escalate when sustained:
- `OPERATIONAL_DIVERGENCE` parity,
- recurring divergence spikes,
- replay determinism violations,
- resurrection-block spikes.

## Rollback
- Disable replay capture and shadow replay validation without changing authoritative legacy behavior.
