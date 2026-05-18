# BunnyCal / easySchedule — Brutal Backend Audit

---

## 1. Executive Summary

You have built a single-tenant, single-host, single-guest scheduling engine with an unusually mature write/sync substrate (transactional outbox, fencing tokens, lifecycle reconciler, snapshot canonicalizer, shadow decision parity, hash-partitioned bookings with per-partition GiST EXCLUDE constraints, optimistic CAS state machine). That substrate is genuinely strong — better than most Series-A scheduling startups.

But the domain model is fundamentally a 1-host × 1-guest Calendly clone. There is no team, no organization, no membership, no host pool, no attendee collection, no participant table, no routing rule, no host-assignment policy, and no concept of "an event with multiple owners." Every invariant, every index, every constraint, every cache key, every webhook path, every projection, every reconciliation lifecycle assumes one host id and one guest email.

The maturity of the plumbing is therefore a trap: it makes the system feel close to multi-party scheduling when it is not. Bolting attendees/hosts onto `bookings(id, host_id)` with the current GiST overlap constraint will break the system's central correctness invariant. You cannot incrementally evolve this. The booking domain needs to be reshaped before any multi-party feature ships.

**Verdict up front:** A (incremental evolution) is not viable for the booking aggregate. A scoped redesign of bookings and its overlap enforcement is required. The surrounding modules (outbox, sync, calendar projection, idempotency, slot engine) mostly survive and are reusable.

---

## 2. Architecture Findings

### 2.1 What is actually well built (do not throw away)

| Area | Why it's good |
| :--- | :--- |
| **Transactional outbox + reaper**<br>`OutboxWorker`, `OutboxReaper`, `outbox_events` | Standard `FOR UPDATE SKIP LOCKED` claim, retry with backoff, stuck-row reaper, processed-events guard. Production-shape. |
| **Idempotency**<br>`IdempotencyService` | Two-phase insert-then-finalize, 3 nested transaction templates, hash mismatch detection, in-progress polling with jitter backoff, failure caching for <500 codes. Genuinely good. |
| **Bookings overlap enforcement** | Hash-partitioned (16) on `host_id`, per-partition GiST `EXCLUDE` with `tstzrange(start_time, end_time)` and predicate status `IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL`. DB is authority, app translates `SQLState 23P01`. Correct, fast, partition-prunable. |
| **Slot engine**<br>`SlotGenerationEngine` | Pure static function, deterministic, half-open intervals, separate buffer pass, grid alignment via `ceilToGrid`, monotonic merge — $O(n \log n)$. Well-structured. |
| **Slot cache + version invalidation**<br>`SlotCacheService`, `SlotCacheVersionService` | Versioned keys, in-flight coalescing without `commonPool` starvation, post-fetch version re-check to discard stale writes (RFC-042 phase 2). Solid. |
| **Calendar sync state machine**<br>`CalendarSyncJob`, `BookingSyncReconciler`, `ProviderEventProjection`, fencing tokens, terminal-delete convergence | This is the most sophisticated part of the codebase. Shadow decisions, parity classifier, canonicalizer hash, persisted reconcile snapshots, drift&rarr;repair enqueue. |
| **Webhook ingestion** | Dedup by delivery key, replay capture, incremental cursor with fallback to full-resync on `SyncTokenInvalidException`, cursor advance is CAS-guarded. |
| **TokenRefresher / encrypted refresh tokens** | Correct shape: short-lived access tokens fetched on demand, no persistence via `AES-GCM` (ciphertext-only). |

### 2.2 What is structurally wrong for multi-party scheduling

Hard, undeniable observations from the code:

1. **`bookings.host_id` is the partition key AND part of the primary key** (`PRIMARY KEY (id, host_id)`). Every read/write path assumes one `host_id` per booking. The repo comment is explicit: *"NEVER add a findById(UUID)-style lookup here. Every read must carry host_id."* (`BookingRepository.java:18`). You cannot model "this booking has 3 hosts" without changing the partition key or denormalizing into a child table — either is a heavy migration.
2. **Guest is two scalar columns** (`guest_email`, `guest_name`) on `bookings` (`V33_0__bookings_add_guest_fields.sql`). No attendees table exists. No relation table. No participant status (accepted/declined/tentative). No multi-attendee join.
3. **The GiST `EXCLUDE` constraint guarantees non-overlap per (`host_id`) partition only** (`V3_0__bookings.sql:101-116`, redefined in `V46_0` with `availability_released_at IS NULL` predicate). For collective scheduling where multiple hosts share a window, you'd need overlap enforcement across $N$ hosts simultaneously. PostgreSQL `EXCLUDE` cannot enforce this on a many-to-many table without either:
    - A row-per-host-per-booking model with `EXCLUDE` on `(host_id, range)`, accepting that the constraint catches overlap per host (this is correct for round-robin / collective, but loses atomic group-write semantics), or
    - An advisory-lock + procedural pre-insert check (worse correctness).

   The current "DB is the sole authority for overlap" invariant disappears the moment multiple hosts can land on one booking.
4. **`EventType` is owned by exactly one user** (`event_types.user_id`, `idx_event_types_user`, slug uniqueness `(user_id, slug)`). No "team event type." No host-assignment policy on the entity. No routing strategy enum.
5. **`AvailabilityRule` and `AvailabilityOverride` are per-user** (`user_id NOT NULL`, FK to users). There is no concept of "team availability" or "intersection availability." The slot engine takes a single rules list and a single override.
6. **`SlotService.compute()` and `SlotGenerationEngine.compute()` are wired for one host.** A `SlotInput` carries one rules list, one override, one event type, one bookings list, one `calendarBusy` list. Multi-host availability is not a "pass more data in" change — it requires intersecting $N$ hosts' free intervals, and for round-robin it requires unioning $N$ hosts' free intervals plus tracking which host owns each slot (the engine returns `SlotUtc(start, end)` with no host identity).
7. **`CalendarConnection` is `UNIQUE(user_id, provider)`** (`V20_0`, `uk_calendar_connections_user_provider`). One Google account per user. Fine for individuals; collective scheduling across host pools will still work only because availability is per-host — but there is no model of "team calendar."
8. **Notifications are 1-host + 1-guest.** `NotificationRecipientResolver.resolveHostRecipient(User host)` returns `Optional<String>`. `resolveAttendeeRecipient(Booking booking)` returns `Optional<String>` from `booking.getGuestEmail()`. There is no loop over attendees.
9. **No teams/orgs/memberships exist anywhere.** A `grep -ri "team\|organization\|round.robin\|collective\|pool"` across `src/main/java` and `db/migration` returns zero matches. There is no foundation. Not even a placeholder table.
10. **`HostDraft` is not a team primitive** — it's a pre-claim onboarding row for a future host (`shadow_user_id`, `claimed_user_id`). It does not generalize to host pools.

---

## 3. Critical Risks (Today, before any new feature)

* **R1. The "all reads must include host_id" rule is already being violated for metrics**
  `BookingService.recordCompletionLatency()` calls `findCreatedAtById(UUID id)` which the repo file even warns against. The method is annotated `LIMIT 1` and *"intentionally matches the same scan pattern as updateStatus."* This works for a partitioned table only because Postgres scans all 16 partitions. At 10M+ bookings, that single read becomes a 16-partition scan on every terminal-state CAS. The known trade-off in `MEMORY.md` confirms you know about it. It's also a precedent — there are now several `WHERE id = :id LIMIT 1` queries that scan all partitions: `findStateById`, `findProjectionStateById`, `findWindowStateById`, `findAnyById`, `findCreatedAtById`. Five of them. Multi-host won't fix this; it will make it worse because group bookings won't have a single `host_id` anyway.
* **R2. The composite `BookingId(id, host_id)` is a leaky abstraction for the caller**
  `BookingRepository.findById(BookingId)` requires callers to know `hostId`. `PublicBookingService.confirm()` constructs `BookingId(bookingId, target.userId())` (`PublicBookingService.java:223`) — fine for single-host. For any multi-host design this signature must change, and every call site must too. There are 5+ call sites that pass `hostId` into `BookingId`. API contracts (`BookingResponse(hostId, ...)`) leak the same assumption to clients.
* **R3. The `createBooking()` flow assumes `outboxPublisher.publish()` will trigger Hibernate auto-flush to surface EXCLUDE violations**
  `BookingService.java:208-213` — this is a comment-documented hack. The catch must enclose `publish()` because the actual `INSERT` only happens when `timeSource.now()` reads the DB clock. This is fragile. Reordering the call (e.g., to batch outbox writes, or to add a pre-flight provider check) silently moves the exception out of the catch and surfaces it as a 500 to clients. For multi-host you will absolutely refactor this code path, and this trap will bite.
* **R4. `confirm()` does pre-flight provider freebusy check then DB CAS — a correctness gap**
  `PublicBookingService.confirm()` (`lines 215–262`) does:
    1. `countConflictsExcludingBooking()` (DB)
    2. `freeBusyService.busyIntervals()` (Google API)
    3. `ensureCalendarEventCreated()` (Google API, may take seconds)
    4. `bookingService.confirmHeldBooking()` (DB CAS `PENDING` &rarr; `CONFIRMED`)

  Between the freebusy check and the CAS, someone else can put a real event on the host's Google calendar. The freebusy call is a TOCTOU (Time-of-Check to Time-of-Use). The DB `EXCLUDE` constraint only protects against other internal bookings, not against external calendar inserts during this 100–2000 ms window. You will double-book. Today. With one host. This becomes catastrophic with multi-host because the window grows linearly in number of hosts checked.
* **R5. `SlotService` computes `calendarBusy` from `calendar_events` table (DB projection), but `PublicBookingService.availability()` also calls `freeBusyService.busyIntervals()` synchronously on every read**
  You're paying for both: DB join AND a synchronous Google round-trip on the read path of the public booking page. At ~200 ms per Google freebusy call, your hot path is provider-bound. The DB projection (`calendar_events`) exists explicitly to avoid this. Either you trust the projection (then drop the live freebusy call) or you don't (then drop the projection). The dual path is the worst of both worlds and confuses the model. With multi-host ($N=5$ hosts), this is $O(N)$ Google calls per public page load.
* **R6. There is no global slot lock during create**
  `createBooking` does `userRepository.findByIdForUpdate(hostId)` to serialize one host's writes. For collective scheduling that lock would have to cover $N$ hosts in deterministic order, otherwise deadlock under contention. The current row-lock pattern doesn't generalize.
* **R7. The booking aggregate carries metadata that conflates internal & external lifecycle**
  `bookings.calendar_sequence`, `bookings.terminal_intent_epoch`, `bookings.availability_released_at` are all properties of the (booking &harr; provider) projection, not the booking itself. With multi-host (multiple providers per booking), these belong on a child table `booking_host_projection(booking_id, host_id, provider, sequence, intent_epoch, released_at)`. Today they live on `bookings`. Migrating is straightforward but invasive.
* **R8. `ReconcileShadowParity` / shadow-decision / canonicalizer / persisted snapshot apparatus is fully aware of one booking &rarr; one provider event**
  `CalendarSyncJob` has a single `external_event_id`, `BookingSyncReconciler.reconcile()` loops jobs one-by-one and observes a single external event. For multi-host scheduling, one logical booking creates $N$ provider events (one per host's calendar). The reconcile pattern still works per (booking, host) but the SQL projections in `BookingRepository.findMeetingsForHost()` (the giant 50-line query joining `calendar_event_mappings` with `LATERAL calendar_sync_jobs`) is wired to one provider per booking: `LEFT JOIN calendar_event_mappings cem ON cem.booking_id = b.id AND cem.provider = 'google'`. The hardcoded `'google'` and the single mapping row per booking is structural.
* **R9. The "freebusy" path uses `connectionRepository.findByUserIdAndStatus(... ACTIVE)` and iterates connections, but `CalendarConnection` is `UNIQUE(user_id, provider)`.** Today this returns &le; 1 row per provider per user. For team scheduling (one host owns multiple work calendars), this assumption persists and is wrong.

---

## 4. Multi-Attendee Readiness (1 host × many guests)

| Question | Reality |
| :--- | :--- |
| **Where does the system store attendees?** | `bookings.guest_email`, `bookings.guest_name`. Two scalars. |
| **Can a booking have N guests today?** | No. Schema does not support it. |
| **Can the availability path tolerate caps (e.g., "this slot has 8 of 10 seats taken")?** | No. There is no capacity model. `EventType` has duration but no capacity, no seats, no `maxAttendees`. |
| **Can two guests book the same slot?** | No — the GiST `EXCLUDE` forbids overlapping `PENDING`/`CONFIRMED` for the same host. This breaks group events outright. The very constraint that makes 1:1 scheduling correct prohibits group events. |
| **Are notifications multi-recipient?** | `NotificationRecipientResolver.resolveAttendeeRecipient()` returns `Optional<String>`, singular. ICS generation produces one `ATTENDEE` line. |
| **API DTOs?** | `PublicBookRequest(startTime, guestEmail, guestName)` — singular fields. `CreateBookingRequest` doesn't even take guest info. |

**Readiness Score: 1/10.** You have nothing for this. Required changes:
* New table `booking_attendees(booking_id, attendee_id, email, name, response_status, added_at, removed_at)` with a unique constraint on `(booking_id, email)`.
* New column `event_types.capacity` (default 1) and `event_types.kind` (`ONE_ON_ONE | GROUP | COLLECTIVE | ROUND_ROBIN`).
* Replace the `EXCLUDE` constraint on bookings with a conditional: only enforce overlap for `event_types.kind = ONE_ON_ONE`. Group events need a seat-count invariant instead, enforced procedurally or with a counted-uniqueness pattern (the latter requires a separate `slot_reservations(host_id, slot_start, slot_end, count, capacity)` table).
* Multi-recipient notification fan-out.
* `bookings.guest_email`/`guest_name` becomes legacy; either drop or denormalize "primary attendee" only.

---

## 5. Multi-Host Readiness (Round-Robin, Collective, Pooled)

### 5.1 Round-robin (one of N hosts gets the booking)
* **Need:** Assignment policy at booking creation; per-host availability evaluated and unioned; chosen host's calendar gets the event.
* **Have:** Nothing. No `host_pool`, no `event_type_host(event_type_id, user_id, weight, priority)`, no assignment service, no weighted/least-recently-booked algorithm.
* **Blocker:** `EventType.user_id` is singular; `Booking.host_id` is singular; partition key is `host_id`. Round-robin can live within this skeleton if you simply pick a host before insert, but you lose the ability to reassign post-creation cleanly (booking is glued to a partition).

**Readiness Score: 2/10.** Doable as the least invasive multi-host feature — add `event_type_hosts` + a `RoundRobinAssigner` service that picks a host before `createBooking()`. But host reassignment after creation requires copying the booking row across partitions (because partition key changes). That's an ugly operation under load.

### 5.2 Collective (all of N hosts must attend)
* **Need:** Intersect $N$ hosts' free intervals; atomically create one booking that occupies all $N$ hosts' calendars simultaneously; non-overlap enforced per host; rollback if any host fails.
* **Have:** Nothing. Slot engine is single-host. Booking is single-host. No SAGA, no two-phase across hosts, no per-host projection.
* **Blocker (critical):** The GiST `EXCLUDE` on `bookings.(host_id, range)` works per host. A collective booking needs one DB write per host, all inside one transaction, each hitting its own partition's GiST. This is workable — Postgres will lock per-partition correctly — but the booking aggregate must be restructured: either:
    * **(a)** One logical booking row + $N$ `booking_hosts` child rows each with their own range copy enforcing per-host overlap, or
    * **(b)** Drop the singular-host booking and make `booking_hosts` the overlap-enforcing table. Option (b) is more correct but is a forklift change.

**Readiness Score: 1/10.** This is the change that breaks the model.

### 5.3 Pooled (any subset of N hosts, assignment at confirmation)
Same blockers as collective + dynamic membership. Not modeled at all.

**Readiness Score: 0/10.**

### 5.4 Team scheduling (org-level admin, shared event types)
* **Need:** Organizations, `org_members(org_id, user_id, role)`, `event_type.org_id` nullable for personal vs team, permission model for who can edit/cancel a team booking.
* **Have:** Users is flat. No tenant. No org. No role. No RBAC. Auth principal is a user UUID.
* **Blocker:** Every authorization check today is `authenticatedHostId.equals(state.getHostId())` (`BookingService.cancelBookingAsHost:301`). There is no org-aware permission service.

**Readiness Score: 0/10.**

---

## 6. Scalability Risks

| # | Risk | Severity | When it bites |
| :--- | :--- | :--- | :--- |
| **S1** | `WHERE id = :id LIMIT 1` queries on bookings scan all 16 partitions. Five such methods. Called on every CAS, every projection read, every confirm/cancel flow. | High | ~1–5M rows per partition; cumulative I/O dominates. |
| **S2** | `bookingRepository.countByStatus("PENDING")` is a Micrometer gauge supplier — scrape-time full-table count. With Prom scraping at 15 s, this is a `COUNT(*)` every 15 s across all partitions filtered by status. | High | As soon as the bookings table grows. There is no upper bound. |
| **S3** | `SlotService.compute()` issues 4 separate queries per cache miss: `userRepository.findById`, `eventTypeRepository.findByIdAndUserId`, `availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc`, `availabilityOverrideRepository.findByUserIdAndDate`, then a bookings range scan, then `calendarBusyTimeService.busyIntervalsForDate` (another query). 6 queries per cache miss. | Medium | First thundering herd after `invalidateUser`. Coalescing helps but doesn't reduce per-miss DB cost. |
| **S4** | `PublicBookingService.availability()` also synchronously calls Google freebusy on the same page load (in addition to DB projection). Hot public booking page is provider-latency-bound. | High | First scale event. Every public visitor pays Google RTT. |
| **S5** | `BookingSyncReconciler.reconcile()` does `Thread.sleep(throttleDelayMs)` (default 25 ms) per job inside a `@Transactional` boundary. That holds a DB connection open during sleep, scales poorly, and the transaction wraps the entire batch loop including a blocking Google API call (`calendarService.observeEvent`). One slow Google call stalls the whole batch and holds the transaction. | Critical | The first time Google takes 5 s on a 50-job batch you exhaust the connection pool. |
| **S6** | `awaitMappingCreated` in `PublicBookingService` polls every 250 ms for 5 s while holding the request thread (and an implicit DB connection if there's still a transaction context). Synchronous wait in HTTP handler. | High | Sync worker latency directly inflates user-visible confirm latency. |
| **S7** | `OutboxWorker` polls every 1 s. At 100 RPS booking creation steady-state, outbox table grows ~360k/hour. Worker can keep up only if its claim batch &times; dispatch rate exceeds insert rate. There is no horizontal partitioning of outbox claims by hash; multiple workers will fight over the same hot rows even with `SKIP LOCKED`. | Medium | Second worker instance brings diminishing returns. |
| **S8** | The big `findMeetingsForHost` projection query joins bookings &bowtie; `event_types` &bowtie; `calendar_event_mappings` &bowtie; `LATERAL (calendar_sync_jobs ORDER BY created_at DESC LIMIT 1)`. The lateral is a per-row sort. No covering index on `calendar_sync_jobs(internal_ref_type, internal_ref_id, provider, created_at DESC)`. | Medium | Dashboard page slow under load. |
| **S9** | `calendar_events` is unbounded; deletion/retention isn't visible. Free/busy reads scan by `(user_id, ends_at > start, starts_at < end)`. Has the right index, but the row count compounds forever. | Low–Medium | Year 2 of operation. |
| **S10** | `SlotCacheService` Redis TTL is hard-coded 60 s with no per-user/per-event-type override. Bursty bookings cause repeated thunders against the DB. | Low | Trade-off, not a bug. |

---

## 7. Concurrency Risks

| # | Risk | Severity |
| :--- | :--- | :--- |
| **C1** | TOCTOU between Google freebusy and DB CAS in `PublicBookingService.confirm()`. Race window: freebusy round-trip + provider event create (potentially seconds). External calendar event inserted in this window will not block the booking. You can double-book today. | Critical |
