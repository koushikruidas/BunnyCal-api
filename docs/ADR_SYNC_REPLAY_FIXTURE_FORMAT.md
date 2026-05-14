# ADR: Replay Fixture Capture Format

## Status
Accepted

## Fixture Record
Each webhook fixture is append-only and includes:
- arrival order (`arrival_index`)
- provider and connection id
- provider event id
- delivery key and payload hash
- raw payload
- dedup result (`FIRST_SEEN` or `DUPLICATE`)
- provider metadata (`provider_updated_at`, `provider_etag`, `provider_sequence`)
- recurring hint
- lineage (`correlation_id`, `causation_id`)
- capture timestamp

## Invariants
- Capture is immutable (insert-only workflow).
- Duplicate deliveries are preserved.
- Ordering metadata is preserved.

## Non-Guarantees
- Fixture data is not guaranteed to encode full provider semantic truth.
- Missing provider metadata may occur and must be treated as advisory.
