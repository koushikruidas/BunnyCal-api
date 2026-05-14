# ADR: Snapshot Lineage and Versioning

## Status
Accepted

## Lineage
Each persisted snapshot includes:
- sync_job_id
- booking_id
- provider/external_event_id
- correlation_id and causation_id
- lineage_source marker

## Versioning
- `snapshot_version` is database-assigned monotonic insertion order for snapshot lineage.
- `snapshot_hash` is deterministic canonical serialization hash.

## Evolution Rule
Any schema-level semantic change affecting snapshot meaning requires ADR update and replay compatibility assessment.
