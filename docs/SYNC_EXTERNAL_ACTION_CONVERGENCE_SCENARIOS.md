# External Action Convergence Scenarios

## Organizer deletes externally
- Expected: projection tombstones; local lifecycle remains canonical.
- Reconcile expectation: `REQUIRE_REPAIR` or converged noop based on desired action.
- Invariant expectation: no illegal composite state; anti-resurrection enforced.

## Attendee declines RSVP externally
- Expected: participation drift signal; no implicit local cancellation.
- Reconcile expectation: parity may diverge, but should classify safely.

## Organizer edits title externally
- Expected: mismatch/drift observation.
- Reconcile expectation: `REQUIRE_REPAIR` or `REQUIRE_RESYNC` in shadow, legacy may remain permissive.

## Organizer edits time externally
- Expected: mismatch/drift with convergence repair path; no direct local authority transfer.

## Provider loses event unexpectedly
- Expected: `MISSING` observation; create repair if local desired action expects existence.

## Duplicate delete/update races
- Expected: deterministic terminal digest under replay; no zombie resurrection.

## Recurring-event mutation behavior
- Expected: tracked as recurring divergence when disorder/ambiguity appears.
- Limitation: full series semantics are not yet reconstructed.
