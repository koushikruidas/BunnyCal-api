# ADR: Recurring Snapshot Semantics

## Status
Accepted (limited support)

## Supported
- recurring hints captured from external event identity/metadata
- recurring divergence metrics emitted during shadow analysis

## Unsupported
- full detached-occurrence semantic reconstruction
- provider-specific recurring series normalization

## Operational Guidance
Recurring snapshot divergence should be reviewed manually using fixture lineage + decision lineage.
