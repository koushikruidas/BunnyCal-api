# Phase 2 Calendar Lifecycle & ICS Authority

## 1. ICS Contract Specification

### Immutable ownership semantics
- `UID` is immutable for the full booking lifecycle: `booking-{bookingId}@{uidDomain}`.
- `ORGANIZER` is application-owned and stable across confirm/update/cancel.
- `ATTENDEE` set is deterministic from host + guest participants.

### Lifecycle semantics
- Confirm/update use `METHOD:REQUEST`, `STATUS:CONFIRMED`.
- Cancel uses `METHOD:CANCEL`, `STATUS:CANCELLED`.
- `SEQUENCE` is monotonic and must advance for each lifecycle mutation.
- `DTSTART/DTEND` remain UTC (`...Z`) to avoid client-local drift.

### Current recurrence boundary
- `RECURRENCE-ID` is not emitted yet in outbound ICS because booking lifecycle is instance-scoped today.
- Recurring/detached instance compatibility remains an explicit later-phase edge case.

## 2. Client Compatibility Matrix

| Client | REQUEST | UPDATE | CANCEL | Organizer Stability | Notes |
|---|---|---|---|---|---|
| Outlook Desktop | Automated ICS invariant tests + MIME shape tests | Automated | Automated | Automated | Inline `text/calendar` + attachment retained for compatibility |
| Outlook Web | Automated ICS invariant tests | Automated | Automated | Automated | Same ICS contract as desktop |
| Gmail | Automated ICS invariant tests | Automated | Automated | Automated | `METHOD/UID/SEQUENCE` contract verified |
| Apple Calendar | Automated ICS invariant tests | Automated | Automated | Automated | UTC event timestamp contract retained |

Validation source in code:
- `IcsInviteGeneratorTest`
- `BookingNotificationServiceTest`

## 3. Lifecycle Reconciliation Guarantees

- Update/delete target resolution is ownership-authoritative:
  - `BookingOwnership.providerExternalEventId` is primary target.
  - Sync-job `externalEventId` is fallback only when ownership link is absent.
- On mismatch between job vs ownership external IDs:
  - mismatch is logged (`lifecycle_authority_external_event_mismatch`)
  - authoritative ownership external ID is used.
- No heuristic attendee/provider search is used for lifecycle mutation targeting.

## 4. Provider Authority Audit Report

Provider lifecycle authority status:
- Google: create/update paths enforce `sendUpdates=none` and emit contract verification logs.
- Microsoft: event payload enforces `responseRequested=false` and emits contract verification logs.
- Application remains sole lifecycle sender via outbound ICS notification pipeline.

Runtime diagnostics:
- `provider_authority_contract_verified`
- `provider_notification_suppression_verified`

## 5. Regression Fixture Suite

Implemented fixture-oriented regression coverage in tests:
- `IcsInviteGeneratorTest.lifecycleUidAndOrganizerRemainStableAcrossRequestUpdateCancel`
- `IcsInviteGeneratorTest.fixtureConferenceProvidersRenderConsistentCalendarFields`
- Existing `BookingNotificationServiceTest` cancellation/inline-calendar coverage.

Fixture dimensions covered:
- REQUEST / UPDATE / CANCEL
- Conferencing URLs representing:
  - Google Meet
  - Microsoft Teams
  - Zoom
  - Custom link
- Organizer/attendee stability
- UID/SEQUENCE/METHOD/STATUS invariants

## 6. Remaining Incompatibility Edge Cases

- Recurring series + detached instance (`RECURRENCE-ID`) lifecycle parity is not yet implemented.
- Cross-client recurrence cancellation nuances remain for later phase matrix expansion.
- Provider-native recurrence edits that mutate external IDs remain outside current lifecycle fixture scope.
