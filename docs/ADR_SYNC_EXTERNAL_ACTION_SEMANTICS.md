# ADR: External Action Semantics (RFC v3 Phase)

## Status
Accepted (foundation scope)

## Canonical Ownership
- Booking lifecycle remains locally canonical.
- Provider events are authoritative observations/signals, not direct lifecycle authority.

## External Action Semantics
1. Organizer external delete:
   - Observed as tombstone signal.
   - Drives deterministic reconcile decision and drift classification.
   - Does not auto-cancel local booking in this phase.

2. Attendee RSVP decline:
   - Captured as participation signal.
   - Does not implicitly cancel local booking.

3. External title/description edits:
   - Treated as projection drift observations.
   - Routed through reconcile decision classification.

4. External time change:
   - Treated as mismatch/drift requiring repair/resync/manual review decision.
   - No aggressive auto-reschedule mutation in this phase.

## Precedence
- Local terminal intent epochs take precedence over stale external observations.
- Reconcile decisions are deterministic and replay-safe from persisted snapshot state.

## Non-Goals
- No generalized policy scripting.
- No provider-authoritative lifecycle mutation.
- No legacy path removal.
