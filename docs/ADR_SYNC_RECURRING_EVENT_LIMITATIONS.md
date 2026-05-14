# ADR: Recurring Event Limitations (Current Phase)

## Status
Accepted (limitations acknowledged)

## Current Capability
- Recurring hints are detected from payload shape and tracked in divergence metrics.
- Recurring fixture streams can be replayed deterministically.

## Limitations
- No full recurring-series semantic reconstruction is performed.
- Instance-vs-series intent interpretation remains limited.
- Provider-specific recurring edge behavior is not fully normalized.

## Operational Guidance
- Treat recurring divergence as a high-signal review path.
- Use fixture lineage and shadow decision logs for manual diagnosis.
