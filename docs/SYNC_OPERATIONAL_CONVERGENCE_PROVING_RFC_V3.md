# Calendar Sync RFC v3 Operational Convergence Proving (Post External Lifecycle Rollout)

Date: 2026-05-14
Scope constraint: validation-only; no architecture expansion, no provider-scope widening, no cutover implementation.

## 1) Operational Proving Report

Evidence executed in this pass:
- `./gradlew test --tests "com.daedalussystems.easySchedule.calendar.replay.*" --tests "com.daedalussystems.easySchedule.sync.reconcile.WebhookReplayConvergenceHarnessTest"`
- Result: 16 passed, 0 failed.

Validated by deterministic harness + runtime logic:
- organizer external delete
- attendee decline
- duplicate webhook delivery
- delayed incremental recovery path logic
- OAuth reconnect flow logic
- OAuth token invalidation handling path (`410` -> full resync path trigger)

Not proven against live provider traffic in this repository run:
- attendee external delete (provider-originated attendee removal as distinct from decline)
- organizer reschedule against real provider webhook semantics
- OAuth invalidation/reconnect with real credentials + webhook history continuity

Conclusion: semantic machinery is stable in replay/harness and code paths are implemented; real-provider proving is still partially open.

## 2) Convergence Validation Results

Observed from replay + harness tests:
- No infinite drift loop signal in replay engine (`invariantViolationCount == 0` across tested scenarios).
- No resurrection after terminal delete (`resurrection_blocked` guards asserted and tested).
- Monotonic lifecycle convergence maintained:
- `projectionVersion` only advances on accepted observations.
- `terminalIntentEpoch` only increments on terminal delete acceptance.
- Replay-safe deterministic digest behavior confirmed (same seed/options => same terminal digest).
- Stable terminal states confirmed (`ACTIVE` vs `CANCELLED` deterministic per scenario).

## 3) Replay Validation Results

### Scenario: Duplicate Delivery
- Initial authoritative state: `ACTIVE`, `projectionVersion=0`, `terminalIntentEpoch=0`.
- Provider observation sequence: `confirmed(seq=1)`, duplicate delivery of same event.
- Projection transitions: first delivery may advance; duplicate is collapsed (`duplicateCollapsedCount++`, projection noop).
- Terminal lifecycle outcome: unchanged from first accepted observation.
- Reconcile suppression outcome: duplicate path is suppressed as replay/noop.
- Monotonic convergence: yes.
- Semantic ambiguity during replay: none.

### Scenario: Out-of-Order Delivery
- Initial authoritative state: `ACTIVE`.
- Provider observation sequence: shuffled sequence vectors with newer + older/equal arrivals.
- Projection transitions: older/equal observations rejected (`staleRejectedCount++`); newer accepted.
- Terminal lifecycle outcome: converges deterministically for same seed.
- Reconcile suppression outcome: stale observations suppressed.
- Monotonic convergence: yes (`projectionVersion` never regresses).
- Semantic ambiguity during replay: possible `AMBIGUOUS_NEWER_HINT` accepted deterministically and counted.

### Scenario: Stale Delete-After-Create
- Initial authoritative state: `ACTIVE`.
- Provider observation sequence: create/update accepted; later stale delete with older/equal vector.
- Projection transitions: stale delete rejected as older/equal.
- Terminal lifecycle outcome: remains `ACTIVE`.
- Reconcile suppression outcome: stale delete suppressed.
- Monotonic convergence: yes.
- Semantic ambiguity during replay: only if vector comparison returns ambiguous hint.

### Scenario: Stale Create-After-Delete
- Initial authoritative state: `ACTIVE`.
- Provider observation sequence: `confirmed(seq=1)` -> `cancelled(seq=2)` -> stale `confirmed(seq=1)`.
- Projection transitions: cancel accepted and raises `terminalIntentEpoch`; stale create blocked by anti-resurrection guard.
- Terminal lifecycle outcome: `CANCELLED`.
- Reconcile suppression outcome: stale resurrection suppressed (`resurrectionBlockedCount++`).
- Monotonic convergence: yes.
- Semantic ambiguity during replay: none in tested fixture; guard resolves conflict deterministically.

### Scenario: Retry Storm
- Initial authoritative state: `ACTIVE`.
- Provider observation sequence: multiplied duplicates + reorder + delayed windows + lane interleaving.
- Projection transitions: high noop/collapse/reject volume; accepted transitions still monotonic.
- Terminal lifecycle outcome: converged and assertion-validated.
- Reconcile suppression outcome: duplicate/stale/revival attempts suppressed.
- Monotonic convergence: yes.
- Semantic ambiguity during replay: may appear as ambiguous vector comparisons and is tracked.

### Scenario: Incremental Recovery Replay
- Initial authoritative state: cursor missing/stale/invalid.
- Provider observation sequence: incremental attempt -> gap suspicion or token invalidation (`410`) -> full recovery fetch -> cursor advance.
- Projection transitions: recovery batch applied, cursor advanced with expected CAS behavior.
- Terminal lifecycle outcome: returns to active sync path.
- Reconcile suppression outcome: N/A for cursor transition itself; observation ingestion remains replay-safe.
- Monotonic convergence: yes for projection/cursor progression.
- Semantic ambiguity during replay: none explicit in tested token-invalid/missing-cursor paths.

## 4) Telemetry Analysis

Instrumentation coverage found (no production timeseries in this repo run):
- Drift frequency:
- `sync.reconcile.drift_detected.total`
- `calendar.sync.provider_drift_detected.total`
- Reconcile suppression rates:
- `sync.reconcile.suppressed.total`
- External delete frequency:
- `sync.reconcile.lifecycle_state.total{state=TERMINAL_EXTERNAL_DELETE}`
- Action-required rates:
- `sync.reconcile.lifecycle_state.total{state=EXTERNAL_ACTION_REQUIRED}`
- Replay rejection rates:
- `webhook_replay_rejected_total`
- `sync.replay.rejected.total`
- Stale replay attempts:
- `sync.replay.rejected.total{reason=older_or_equal}`
- `sync.replay.resurrection_blocked.total`

Assessment: telemetry schema is sufficient for requested analysis, but this pass did not include live metric extraction from prod/stage.

## 5) Unresolved-Risk Inventory

1. Real-provider semantic gap:
- Deterministic replay/harness is proven; real Google webhook disorder and attendee-delete subtleties are only partially evidenced.
2. Ambiguous provider ordering:
- `AMBIGUOUS_NEWER_HINT` is accepted deterministically, but policy sensitivity remains for edge payload churn.
3. Persisted snapshot completeness debt (already tracked in repo docs):
- Full cross-domain persisted snapshot semantics remain deferred risk for reconciliation explainability.
4. Reconnect continuity:
- OAuth reconnect logic exists; continuity of long-gap provider history across reconnect still requires live proving.
5. Legacy runtime dependency:
- External lifecycle semantics are feature-flag gated; fallback behavior remains coupled to legacy decision path until cutover.

## 6) Runtime Cutover Recommendation

Recommendation: `NOT READY FOR FULL CUTOVER` yet.

Go/No-Go criteria still open:
- Complete live-provider validation matrix for:
- organizer external delete
- attendee external delete
- organizer reschedule
- attendee decline
- duplicate webhook delivery
- delayed incremental recovery
- OAuth invalidation
- reconnect continuity
- Collect and review production/stage telemetry baselines for drift/suppression/replay-reject/action-required rates across at least one disorder window.
- Confirm no unresolved semantic ambiguity in live replay traces for stale create-after-delete and out-of-order storms.

## 7) Rollback Confidence Assessment

Assessment: `HIGH rollback confidence`, `MEDIUM cutover confidence`.

Why rollback confidence is high:
- External lifecycle semantics are behind `sync.reconcile.external-lifecycle.enabled`.
- Legacy runtime remains available.
- Observability is broad (replay, drift, suppression, lifecycle state counters).

Why cutover confidence is not yet high:
- Remaining real-provider proving gaps above.
- Telemetry review in this pass is instrumentation-level, not environment-level timeseries validation.
