# Booking System Contracts

## 1. Purpose

This document is the authoritative specification for the booking subsystem's
invariants, lifecycle, and timing rules. It is paired with code-level
enforcement under `booking/contract/` and `common/time/`. If this document and
the code disagree, the code wins — and one of them is a bug.

This file contains **no business logic and no schema**. It defines what the
booking system is allowed to promise, not how any particular flow implements
those promises.

---

## 2. Global Invariants

| #  | Invariant                                                                                            | Code-level enforcer                                                |
|----|------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| 1  | No two bookings overlap for the same host when status reserves a time slot                          | `BookingState.blocksTimeSlot()`                                    |
| 2  | Every booking must eventually reach a terminal state                                                 | `BookingState.isTerminal()` + `BookingContracts.BOOKING_PENDING_TTL` |
| 3  | Idempotent requests produce exactly one booking                                                      | `BookingContracts.IDEMPOTENCY_KEY_TTL`                             |
| 4  | No committed booking is ever lost                                                                    | DB durability + `@Transactional` write path                        |
| 5  | Expired bookings cannot transition back to active states                                             | `BookingStateTransitions` — terminal states have empty out-sets    |
| 6  | Async processing is at-least-once but the effect must be exactly-once                                | `BookingStateTransitions.requireAllowed` self-transition no-op     |

---

## 3. State Model

| State      | `isTerminal` | `blocksTimeSlot` | Notes                                     |
|------------|--------------|------------------|-------------------------------------------|
| PENDING    | false        | true             | Initial state. Holds capacity until TTL.  |
| CONFIRMED  | false        | true             | Active booking. Holds capacity.           |
| CANCELLED  | true         | false            | User- or host-cancelled.                  |
| EXPIRED    | true         | false            | PENDING booking that exceeded TTL.        |
| COMPLETED  | true         | false            | Event ran to its scheduled end.           |
| REJECTED   | true         | false            | Host explicitly declined.                 |

### Adding a new state

If you add a new state that reserves capacity (e.g. `HOLD`,
`RESCHEDULE_PENDING`), it **MUST** declare `blocksTimeSlot = true`. Forgetting
this silently breaks Invariant #1. The cross-check unit test
(`!(isTerminal && blocksTimeSlot)` for every state) is your guard rail; if it
fails, fix the state metadata, not the test.

---

## 4. State Transition Diagram

```
                            ┌─────────────┐
                            │   PENDING   │
                            └──────┬──────┘
                  ┌────────────────┼────────────────┐
                  │                │                │
                  ▼                ▼                ▼
          ┌────────────┐   ┌────────────┐   ┌────────────┐
          │ CONFIRMED  │   │ CANCELLED  │   │  EXPIRED   │
          └─────┬──────┘   └────────────┘   └────────────┘
                │                │
                ├────────────────┤    PENDING also → REJECTED (terminal)
                ▼                ▼
        ┌────────────┐   ┌────────────┐
        │ COMPLETED  │   │ CANCELLED  │
        └────────────┘   └────────────┘
```

Allowed transitions (authoritative — same data lives in
`BookingStateTransitions.TRANSITIONS`):

- `PENDING   → { CONFIRMED, CANCELLED, EXPIRED, REJECTED }`
- `CONFIRMED → { CANCELLED, COMPLETED }`
- `CANCELLED, EXPIRED, COMPLETED, REJECTED → ∅`
- Self-transitions (`s → s` for any state) are no-ops, not errors — see Invariant #6.

Notably **forbidden**:

- `CONFIRMED → EXPIRED` — once confirmed, a booking does not expire; it either completes or is cancelled.
- `PENDING → COMPLETED` — a booking must be confirmed before it can complete.
- Any transition out of a terminal state.

---

## 5. Time Source Policy

The database is the **only** sanctioned time source. All time-sensitive
decisions (state transitions, expiry checks, overlap windows, idempotency-key
TTLs, retry deadlines) MUST resolve "now" through `common/time/TimeSource`.

The following are **forbidden** in any code that performs the above:

- `Instant.now()`
- `System.currentTimeMillis()`
- `LocalDateTime.now()` / `LocalDate.now()` / `OffsetDateTime.now()`
- `Clock.systemUTC()` / `Clock.systemDefaultZone()`
- `new Date()`

**Enforcement:** any usage of the above in `booking/` or `availability/`
**must fail code review.** Documentation alone is weak; the planned future
enforcement is one of:

- An ArchUnit test asserting no static `now()` calls in those packages.
- An Error Prone bug pattern flagging the same.

Either is a follow-up; not part of the contracts PR.

---

## 6. Retry / Timeout Policy

All values are defined in `BookingContracts`. They are split into two groups:

### Protocol constants (do not env-tune)

| Constant              | Value          | Why                                                       |
|-----------------------|----------------|-----------------------------------------------------------|
| `MAX_ASYNC_RETRIES`   | 5              | Bounded retries protect Failure Domain Rule on degradation. |
| `BOOKING_PENDING_TTL` | 15 minutes     | Forces unconfirmed bookings to a terminal state (Invariant #2). |
| `IDEMPOTENCY_KEY_TTL` | 24 hours       | Window inside which a duplicate request is de-duplicated (Invariant #3). |

### Operational tuning candidates (may move to config later)

| Constant                | Value          | Why                                                  |
|-------------------------|----------------|------------------------------------------------------|
| `DB_LOCK_TIMEOUT`       | 5 seconds      | Bounds row-lock waits during booking creation.       |
| `ASYNC_TASK_TIMEOUT`    | 30 seconds     | Per-attempt cap on an async side-effect.             |
| `RETRY_INITIAL_BACKOFF` | 500 ms         | First retry delay; exponential growth from here.     |
| `RETRY_MAX_BACKOFF`     | 10 seconds     | Caps the exponential backoff curve.                  |

---

## 7. Failure Domain Rules

- **Async failures must not block booking creation.** The synchronous create
  path commits the booking; downstream side-effects (notifications, calendar
  sync, analytics) run async and may fail independently.
- **At-least-once delivery, exactly-once effect.** Async workers may retry up
  to `MAX_ASYNC_RETRIES`. The effect must be idempotent — typically by keying
  off a stable booking id and using `requireAllowed`'s self-transition no-op
  to absorb duplicates.
- **Graceful degradation under load.** Lock waits are bounded by
  `DB_LOCK_TIMEOUT`; per-attempt async work is bounded by
  `ASYNC_TASK_TIMEOUT`. The system fails fast rather than queuing work
  unboundedly.

---

## 8. Consistency Model

- **Write path: STRONG.** Booking creation and state transitions are enforced
  by the database (row locks, transactional commit, eventual unique/exclusion
  constraints). The DB is the source of truth.
- **Read path: EVENTUAL.** Read-side caches (e.g. slot availability) may lag
  the write side. They are invalidated on mutation but consumers must not
  treat reads as authoritative.

---

## 9. Non-Negotiables

- This module contains **no business logic** and **no database schema**.
- Any code performing a state mutation **MUST** go through
  `BookingStateTransitions.requireAllowed` (or its overload). There is no
  other sanctioned path.
- Any code resolving "now" **MUST** go through `TimeSource`.
- Constants live in `BookingContracts`. Inline magic numbers in the booking
  flow are a contract violation.

---

## 10. Idempotency Key Lifecycle

### Scope

Idempotency keys are scoped by `(user_id, route, key)`.

- Same key from different users is independent.
- Same key for the same user on different routes is independent.

### State model

`IN_PROGRESS -> COMPLETED | FAILED`

Terminal rows are replayable until `IDEMPOTENCY_KEY_TTL` expiry.

### Transaction boundaries

- Phase 1 (`REQUIRES_NEW`): insert `IN_PROGRESS` marker.
- Phase 2 (default TX): run booking work and finalize terminal row.
- Phase 3 (`REQUIRES_NEW`): cache deterministic failures for replay.

### Ordering and race rules

- Request-hash mismatch is checked before polling.
- Reaper uses `updated_at < now - IDEMPOTENCY_PROCESSING_TIMEOUT` and `completed_at IS NULL`.
- Polling uses full-jitter exponential backoff and must satisfy:
  `IDEMPOTENCY_POLL_TOTAL < IDEMPOTENCY_PROCESSING_TIMEOUT`.

### Replay contract

Replays return stored `response_status` + `response_body` and include
`Idempotency-Replayed: true`.

### Response size cap

`MAX_CACHED_RESPONSE_BYTES = 16 KiB` caps cached body size.
If a fresh response exceeds the cap, the service stores a replay-safe minimal
success body instead of failing idempotency replay.

### Observability

- `idempotency_outcome_total{outcome=...}`
- `idempotency_replay_latency`
- `idempotency_in_progress_polls_total`
- `idempotency_finalize_race_total`
