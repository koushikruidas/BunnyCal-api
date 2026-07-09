# Calendar Sync — Scaling Flags & Webhook-Health Runbook

**Audience:** operators / on-call.
**Scope:** the five env flags that control calendar-sync throughput and polling
cadence, how they interact, how to verify webhook delivery is healthy before/while
trusting it, and how to roll back instantly with no redeploy.

Companion doc: `CALENDAR_SYNC_OPERATIONAL_MODEL.md` (the sync model end-to-end).
Code of record: `CalendarSyncScheduler.java`, `ProviderConcurrencyGate.java`,
`CalendarConnectionRepository.findDueForSyncBatchGated`.

---

## TL;DR

| Flag | Prod value | What it controls |
|---|---|---|
| `CALENDAR_WEBHOOK_ENABLED` | `true` | Master switch for webhooks (registers watch channels, runs renewal). |
| `CALENDAR_SYNC_PARALLEL_ENABLED` | `true` | Whether a sweep processes connections in parallel vs. one-at-a-time. |
| `CALENDAR_SYNC_PARALLELISM` | `8` | Worker-pool size for the parallel sweep. |
| `CALENDAR_SYNC_WEBHOOK_FRESH_GATING_ENABLED` | `true` | **The flag that stops the 30s poll** for webhook-healthy connections. |
| `CALENDAR_SYNC_WEBHOOK_FRESH_BACKSTOP` | `PT15M` | Backstop poll cadence for webhook-fresh connections. |

Supporting tunable: `CALENDAR_SYNC_SWEEP_MAX_RUNTIME` (`PT25S`) — hard per-sweep
deadline; **must stay well below** the scheduler's ShedLock `lockAtMostFor` (`PT2M`).

**Instant rollback:** set `CALENDAR_SYNC_WEBHOOK_FRESH_GATING_ENABLED=false` →
everything polls every 30s again. Set `CALENDAR_SYNC_PARALLEL_ENABLED=false` →
original sequential sweep. Both are env-only; **no redeploy, no code change**.

---

## Does this config stop the every-30s poll?

**Partly — and that nuance is the whole point.** With gating on, only connections
we can *trust to the webhook* drop off the 30s cadence. Everything else keeps the
30s safety net. This is the exact `findDueForSyncBatchGated` WHERE clause:

| Connection state | Cadence with gating ON |
|---|---|
| Fresh webhook channel **and** synced within the backstop window | **backstop (15 min)** — poll skipped |
| No webhook channel (`webhook_channel_expires_at IS NULL`) | every **30s** |
| Expired webhook channel | every **30s** |
| Never synced (`last_synced_at IS NULL`) | every **30s** |
| Fresh channel but last sync older than the backstop | due now (backstop elapsed) |
| `FAILED` / `ERROR` | unchanged — follows `next_retry_at` backoff |
| `REVOKED` | never polled |

So the true statement is: *"connections with a healthy webhook stop polling every
30s; everything we can't trust to the webhook keeps the 30s poll."* No connection
is ever dropped — the worst case for a fresh-channel connection whose webhook
silently fails is **backstop-interval staleness (15 min)**, not lost data.

---

## Flag reference — behavior at each setting

### 1. `CALENDAR_WEBHOOK_ENABLED`
Master switch for the entire webhook subsystem: registering Google watch channels /
Microsoft subscriptions at connect time, and running the renewal schedulers.

| Value | Behavior |
|---|---|
| `true` | App subscribes to providers and receives real-time change notifications. **Required** for gating to do anything. |
| `false` | No webhooks at all → every connection has a null channel → **everything polls every 30s.** If this is `false` while gating is `true`, gating is a silent no-op (nothing is ever "webhook-fresh"), so you fall back to full 30s polling anyway. |

**Rule:** gating (#4) only has an effect when this is `true`.

### 2. `CALENDAR_SYNC_PARALLEL_ENABLED`
How the sweep drains due connections — sequential vs. a worker pool. Orthogonal to
polling frequency; this is about throughput per sweep.

| Value | Behavior |
|---|---|
| `true` | Sweep fans out to `PARALLELISM` workers. Lifts the sustainable ceiling from ~150–300 to ~1,000 connected calendars at parallelism 8. |
| `false` | Original sequential loop, byte-for-byte. Safe rollback, no redeploy. |

Toggling this changes *how fast* a sweep drains, not *what* is polled.

### 3. `CALENDAR_SYNC_PARALLELISM`
Worker-pool size when parallel is on. Default `8`.

- **Higher** (16, 32) → more connections per sweep → higher ceiling, but more
  concurrent load on the **Hikari pool (30 connections, shared with request
  traffic)** and more provider API fan-out. Keep **≤ 32** (the per-provider
  `ProviderConcurrencyGate` cap) so the gate — not the pool — bounds provider calls.
- **Lower** (4) → gentler on DB/providers, lower ceiling.
- Before raising: watch `calendar.sync.sweep.duration`,
  `calendar.sync.sweep.deadline_hit.total`, and
  `calendar.sync.sweep.unprocessed_due`.

### 4. `CALENDAR_SYNC_WEBHOOK_FRESH_GATING_ENABLED` — *the flag that stops the 30s poll*

| Value | Behavior |
|---|---|
| `true` | Sweep uses `findDueForSyncBatchGated`. Webhook-fresh connections skip the 30s poll and rely on the webhook + the backstop. **This is the capacity win and the risk.** |
| `false` | Uses `findDueForSyncBatch`. **Everything polls every 30s** regardless of webhook state. Instant, redeploy-free fallback. |

**The risk to internalize:** with this `true`, you are trusting the webhook as the
real-time path. If webhook delivery silently breaks — most likely **Microsoft**,
whose subscriptions expire every **~2 hours** and depend on the renewal scheduler
succeeding — a fresh-but-undelivered change won't be picked up until the next
backstop poll (up to 15 min) instead of within 30s. Only run this `true` while
webhook delivery is confirmed healthy (see the health checklist below). On any doubt:
set it `false`.

### 5. `CALENDAR_SYNC_WEBHOOK_FRESH_BACKSTOP`
How often webhook-fresh connections poll as a **safety net** even when trusting the
webhook. Default `PT15M`.

- **Shorter** (`PT5M`) → safer if webhooks flake (max staleness 5 min), but more
  polling load → smaller capacity win.
- **Longer** (`PT30M`) → bigger capacity win, but a webhook outage means up to
  30 min of staleness.
- `PT15M` balances both: a broken webhook is caught within 15 min while eliminating
  ~29 of every 30 polls for healthy connections.

### Supporting: `CALENDAR_SYNC_SWEEP_MAX_RUNTIME`
Hard per-sweep deadline (default `PT25S`). Guarantees each sweep returns inside the
ShedLock window so two replicas can never run overlapping sweeps.

> **Invariant — do not break:** `SWEEP_MAX_RUNTIME` **must stay well below**
> `lockAtMostFor` (`PT2M`). The same connection is protected from concurrent
> processing only because the sweep provably finishes before the lock could be
> handed to another replica. Do not raise this near `PT2M`.

---

## Webhook-health checklist — confirm BEFORE (and while) gating is ON

Gating is only safe while webhook delivery works. Verify these signals. All are
grep-able log keys from the codebase (log names are the code of record).

### Green — webhooks are working
| Signal | Where | Meaning |
|---|---|---|
| `calendar_webhook_ingested` | `CalendarWebhookIngestionService` | A webhook notification was received **and** the change was pulled + applied. **Primary success signal — this should keep flowing for both providers.** |
| `webhook_received provider=google` | `CalendarIntegrationController:224` | A Google notification landed at our endpoint. |
| `calendar_watch_recovered provider=google\|microsoft` | renewal schedulers | A lapsed/expiring channel was successfully re-subscribed. |

> Note: the **Microsoft** receive path does not emit a distinct `webhook_received`
> line the way Google does — for Microsoft, rely on `calendar_webhook_ingested` as
> the delivery-working signal.

### Red — webhooks are failing (flip gating OFF)
| Signal | Where | Meaning |
|---|---|---|
| `microsoft_watch_renewal_failed` | `MicrosoftWatchChannelRenewalScheduler:133` | Microsoft subscription renewal failing. **Highest-priority alarm** — MS channels expire every ~2h, so this quickly means no real-time delivery. |
| `google_watch_renewal_failed` | `GoogleWatchChannelRenewalScheduler:119` | Google watch renewal failing (channels expire ~7d, more slack). |
| `calendar_watch_renewal_failure_tracking_failed` | both schedulers | Couldn't even record a renewal failure — investigate DB/write path. |
| `calendar_webhook_orphan_channel` | `CalendarIntegrationController:289` | A notification arrived for a channel with no matching connection. |
| **Absence** of `calendar_webhook_ingested` over a normally-active window | — | Silent webhook death. The most dangerous case because nothing errors. |

**Decision rule:** if you see sustained `*_watch_renewal_failed` (especially
Microsoft), or `calendar_webhook_ingested` stops flowing while calendars are known
to be changing → **set `CALENDAR_SYNC_WEBHOOK_FRESH_GATING_ENABLED=false`
immediately.** You're back to full 30s polling with no redeploy; capacity drops but
correctness is restored.

### Metrics (if OTLP export is enabled)
| Metric | Meaning / when to worry |
|---|---|
| `calendar.sync.sweep.duration` | Sweep wall-time. Should be comfortably under `SWEEP_MAX_RUNTIME`. |
| `calendar.sync.sweep.deadline_hit.total` | Sweeps that stopped early on the deadline. Sustained increments = falling behind; raise `PARALLELISM` (with headroom) or investigate slow providers. |
| `calendar.sync.sweep.unprocessed_due` | Due connections not processed last sweep (deadline or gate-deferred). Should hover near 0. |
| `calendar.sync.concurrency.deferred.total` (tag `provider`) | Provider gate saturation. Spikes on one provider = that provider is the bottleneck. |
| `calendar.sync.per_connection.duration` | Per-connection provider call time. |
| `calendar.sync.cursor_conflict.total` (tag `source`) | Webhook vs. poll racing the same connection — expected to be low; the losing writer no-ops safely. |

> Prod note: metric export is gated by `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED`
> (currently `false`). Until that's on, the **log keys above are the operational
> source of truth.** Ensure the calendar service/controller loggers are at `INFO`
> so `calendar_webhook_ingested` and `webhook_received` are actually emitted.

---

## Rollout & rollback

**Recommended enable order** (each step is env-only, revertable):

1. `CALENDAR_WEBHOOK_ENABLED=true` — let watch channels register and renew. Confirm
   `calendar_webhook_ingested` is flowing and no `*_watch_renewal_failed`.
2. `CALENDAR_SYNC_PARALLEL_ENABLED=true` (parallelism `8`) — watch
   `sweep.duration` drop and `deadline_hit` / `unprocessed_due` stay ~0.
3. Only once #1 is confirmed healthy: `CALENDAR_SYNC_WEBHOOK_FRESH_GATING_ENABLED=true`.
   Watch that `calendar_webhook_ingested` keeps flowing — that's what real-time
   delivery now depends on.

**Rollback (no redeploy):**

| Symptom | Action |
|---|---|
| Webhooks failing / stale calendars | `CALENDAR_SYNC_WEBHOOK_FRESH_GATING_ENABLED=false` |
| Sweep overloading DB / providers | `CALENDAR_SYNC_PARALLEL_ENABLED=false` (or lower `PARALLELISM`) |
| Want the fully original behavior | both above `false` → sequential every-30s sweep |

---

## Capacity summary

| Configuration | Sustainable connected calendars (approx.) |
|---|---|
| Sequential, no gating (original) | ~150–300 |
| Parallel (parallelism 8), no gating | ~1,000 |
| Parallel + gating (webhooks healthy) | higher still — 30s polling load removed for healthy connections; tunable toward ~2,000–4,000 with Hikari-pool / provider-rate-limit headroom checks |

Gating's capacity gain is **conditional on webhook delivery staying healthy.** Treat
`calendar_webhook_ingested` (present) and `*_watch_renewal_failed` (absent) as the
two signals that keep that assumption true.
