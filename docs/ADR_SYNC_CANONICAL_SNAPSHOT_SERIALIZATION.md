# ADR: Canonical Snapshot Serialization Contract (RFC v3 Phase)

## Status
Accepted (v1 contract)

## Purpose
Define a stable canonicalization contract for `sync_reconcile_decision_log.input_hash` used in replay equivalence and audit correlation.

## Contract
- Canonical payload is a fixed ordered field registry serialized as JSON.
- Field order is explicit by registry construction, not reflection order.
- Contract includes `schemaVersion` and is versioned.
- String fields are Unicode-normalized to NFC before serialization.
- Enum fields are serialized using enum names.
- Nullability policy:
  - required string-like fields are normalized to empty string (`""`) if null
  - nullable numeric fields remain JSON `null`
- No ambient application `ObjectMapper` is used for canonicalization.

## Non-Goals
- No backward hash compatibility guarantee across schema version changes.
- No inference of semantic equality between different schema versions.

## Evolution Rule
Any canonical field-add/remove/reorder or null-policy change must increment `schemaVersion` and be treated as a deliberate replay-audit contract change.
