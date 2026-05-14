# ADR: Provider Replay Assumptions

## Status
Accepted

## Assumptions
- Providers may deliver duplicates, reorder updates, and emit ambiguous metadata changes.
- Provider timestamps/etag/sequence are advisory and can be inconsistent.
- Raw webhook payload capture is required for realistic replay proving.

## Guardrails
- Local terminal intent remains canonical for anti-resurrection behavior.
- Projection acceptance applies per-key monotonic rules only.
- Replay determinism cannot rely on wall-clock or thread scheduling.

## Known Unknowns
- True disorder distribution by provider/tenant over long windows.
- Recurring-event edge behavior under delayed updates/deletes.
