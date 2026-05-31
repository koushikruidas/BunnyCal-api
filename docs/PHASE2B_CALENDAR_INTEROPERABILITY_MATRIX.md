# Phase 2B Calendar Interoperability Matrix

Date: 2026-05-26
Supported clients in scope:
- Outlook Desktop
- Outlook Web
- Gmail / Google Calendar
- Microsoft Calendar / Exchange

## Execution matrix (automated harness coverage)

| Client target | REQUEST | UPDATE | RESCHEDULE | CANCEL | Organizer continuity | Duplicate prevention | Notes |
|---|---|---|---|---|---|---|---|
| Outlook Desktop | Pass (MIME/ICS assertions) | Pass | Pass | Pass (CANCEL method + suppression) | Pass | Pass | Multipart + inline text/calendar contract validated |
| Outlook Web | Pass (MIME/ICS assertions) | Pass | Pass | Pass | Pass | Pass | Same MIME contract path as desktop |
| Gmail / Google Calendar | Pass (REQUEST/UPDATE/CANCEL ICS semantics) | Pass | Pass | Pass | Pass | Pass | Organizer/UID/SEQUENCE invariants verified |
| Microsoft Calendar / Exchange | Pass (authority + response suppression contract) | Pass | Pass | Pass | Pass | Pass | responseRequested=false contract verified |

## Tested lifecycle behaviors

For each supported target profile, automation validates:
- REQUEST creates reconcilable event identity (`UID` stable)
- UPDATE/RESCHEDULE mutate existing identity (no new UID)
- CANCEL emits proper cancel semantics and suppresses active meeting affordances
- Organizer remains application-owned and stable
- No duplicate projection writes from retry/stale ownership paths

## Versions/platforms

Automated tests validate protocol-level interoperability semantics from generated MIME/ICS payloads.
Live human-in-the-loop mailbox UI tests are deferred to operational QA runbooks (outside this repository automation).

## Observed provider quirks and workarounds

- Outlook requires robust multipart structure with inline `text/calendar` for best auto-reconciliation.
- Google and Microsoft provider APIs may include organizer/attendee fields in mirrored events; lifecycle authority remains app-side through explicit suppression flags and app-owned ICS.
- Cancellation messages are intentionally stripped of conferencing CTA/active URLs to prevent stale “join” affordances.

## Required runtime diagnostics

Added lifecycle diagnostics in notification pipeline:
- `organizer_authority_verified`
- `organizer_authority_mismatch_detected`
- `lifecycle_client_reconciliation_verified`

Fields include:
- bookingId
- provider
- externalEventId (if known)
- organizerIdentity
- clientType

## MIME compatibility hardening

Validated in tests:
- multipart ordering and nested alternative shape
- inline `text/calendar` presence
- attachment fallback preserved
- method-specific semantics (`REQUEST` vs `CANCEL`)
- cancellation rendering suppression of active meeting affordances

## Timezone interoperability coverage

Added lifecycle UTC/DST invariants:
- UTC timestamp stability around DST boundaries
- no implicit local-hour drift in ICS DTSTART/DTEND rendering

## Known deferred recurrence lifecycle risks

Recurrence remains intentionally deterministic and non-heuristic. Deferred risks are explicit:
- detached instance lifecycle (`RECURRENCE-ID`) ownership lineage not yet implemented
- partial-series cancellation semantics not fully modeled
- series-vs-instance mutation reconciliation remains later-phase work
- no attendee/fuzzy/provider-search heuristics will be introduced

## Regression fixture capture

Automated fixture-oriented coverage captures client-facing artifacts in tests for:
- raw REQUEST MIME
- raw UPDATE MIME
- raw CANCEL MIME

Across combinations of:
- projection provider (Google/Microsoft)
- conferencing provider (Meet/Teams/Zoom/Custom)

Assertions verify organizer continuity, MIME method semantics, conference rendering stability, and cancellation suppression.
