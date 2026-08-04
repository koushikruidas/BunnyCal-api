# Disaster Recovery Runbook

Read this when the database is gone, corrupted, or needs to move. It assumes you
are stressed and skimming, so the commands are copy-pasteable and the traps are
called out inline.

---

## 0. What exists, and which one to reach for

| Layer | Covers | RPO | Where |
|---|---|---|---|
| **Managed provider backups + PITR** | disk failure, bad migration, accidental `DELETE`, dropped table | seconds–minutes | provider console |
| **Nightly `pg_dump` (encrypted, off-site)** | losing the provider account, moving cloud, major-version upgrade | ≤ 24 h | `$BACKUP_RCLONE_REMOTE` |

**Reach for provider PITR first for almost everything.** It is faster, loses less
data, and needs no local tooling.

Use the logical dump only when the provider itself is the problem — account
suspension, billing lockout, compromised credentials, or a deliberate migration.
Provider backups are tied to the instance: delete the instance and they go with
it. That is the exact gap the nightly dump covers.

**Redis is never restored.** It holds only TTL'd cache (slot cache, slot version
counters, OAuth access tokens) and every call site fails open to Postgres. A
wiped Redis costs about 60 seconds of slower slot lookups. Do not spend outage
time on it.

---

## 1. Before you restore anything: the key

A perfect database restore is **useless without `CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64`.**

`AesGcmTokenCipher` encrypts every Google/Microsoft/Zoom refresh token
(`calendar_connections.refresh_token_ciphertext`,
`zoom_conferencing_connections.refresh_token_ciphertext`) with AES-256-GCM using
that single key. Restore with a different key and:

- every calendar connection fails to decrypt,
- every user must manually reconnect their calendar,
- **and nothing tells you** until the first sync attempt fails.

There is no key versioning today, so there is no second key to fall back to.

**Confirm you have the exact key from the password manager before you start.**
If you are restoring into a new environment, this is the value to copy first.

Also required for the app to boot: `JWT_SECRET`,
`CALENDAR_WEBHOOK_SHARED_SECRET`, `CALENDAR_OAUTH_STATE_SECRET`,
`APP_EMBED_TOKEN_SECRET`.

---

## 2. Restore from the provider (normal case)

Use the provider console: pick a point in time, restore to a **new** instance,
then repoint the app. Do not restore in place — keeping the damaged instance
around preserves evidence and gives you a way back.

```bash
# 1. Restore to a new instance in the provider console (PITR to just before the incident).
# 2. Point the app at it:
#    edit SPRING_DATASOURCE_URL in the GitHub `production` env secret PRODUCTION_ENV_FILE
#    (keep ?sslmode=verify-full)
# 3. Redeploy:
gh workflow run deploy-prod.yml
```

Then run the verification in §4 and the reconciliation in §6.

---

## 3. Restore from the nightly dump

Use when the provider is unavailable or you are moving off it.

```bash
# --- Fetch and decrypt --------------------------------------------------
rclone copy "$BACKUP_RCLONE_REMOTE/bunnycal-2026-08-20.dump.age" .
age -d -i /path/to/backup-age.key bunnycal-2026-08-20.dump.age > bunnycal.dump

# --- Fresh cluster ------------------------------------------------------
docker run -d --name pg-restore \
  -e POSTGRES_USER=bunnycal \
  -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  -e POSTGRES_DB=bunnycal \
  -p 5433:5432 \
  postgres:16-alpine

# --- Extensions FIRST ---------------------------------------------------
# btree_gist MUST exist before restore: the bookings partitions carry GiST
# EXCLUDE constraints and their creation fails without it. This step is
# mandatory, not decorative.
docker exec -i pg-restore psql -U bunnycal -d bunnycal <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;
SQL

# --- Restore ------------------------------------------------------------
docker exec -i pg-restore pg_restore \
  -U bunnycal -d bunnycal \
  --no-owner --no-privileges \
  --jobs=4 --exit-on-error --verbose \
  < bunnycal.dump
```

### Traps specific to this schema

- **`bookings` is HASH-partitioned into 16 partitions** (`bookings_p00`–`p15`,
  `V3_0__bookings.sql`), each with its own GiST `EXCLUDE` constraint preventing
  double-booking.
  - **Never `--table=bookings`** — it silently misses all partition data.
  - **Never `--data-only`** into a Flyway-built schema. Restore schema and data
    together.
  - The `EXCLUDE` GiST indexes are built after data load and dominate restore
    time. Slow is expected; failing is not.
- **Two standalone sequences owned by no column** — a hand-rolled table-by-table
  copy resets them silently:
  - `subscription_invoice_number_seq` — **gapless invoice numbers. Rewinding this
    corrupts financial records and produces duplicate invoice numbers.**
  - `sync_fencing_token_seq`
  A full `pg_dump`/`pg_restore` preserves both. This is the main reason not to
  improvise a partial restore.
- **`user_avatars.image_data` is `BYTEA`** — all user uploads live in Postgres.
  There is no object store to restore alongside; the dump is self-contained.

---

## 4. Verify the restore

Run all of it. Each line has an expected value.

```sql
-- Flyway state: 143 rows, latest 142, all successful
SELECT count(*) AS applied,
       max(version::numeric) AS latest,
       bool_and(success) AS all_ok
FROM flyway_schema_history;

-- All 16 booking partitions attached
SELECT count(*) FROM pg_inherits WHERE inhparent = 'bookings'::regclass;   -- 16

-- Double-booking guards intact, one per partition
SELECT count(*) FROM pg_constraint
WHERE contype = 'x' AND conrelid::regclass::text LIKE 'bookings_p%';       -- 16

-- No broken indexes
SELECT count(*) FROM pg_index WHERE NOT indisvalid;                        -- 0

-- Avatars not truncated
SELECT count(*) FILTER (WHERE length(image_data) = 0) FROM user_avatars;   -- 0

-- Sequences did not rewind (compare against pre-incident values if known)
SELECT last_value FROM subscription_invoice_number_seq;
SELECT last_value FROM sync_fencing_token_seq;

-- Sanity
SELECT count(*) FROM users;
SELECT count(*) FROM bookings;
```

### The token-decrypt canary

The only check that catches a wrong encryption key. Boot the app against the
restored database with the candidate key and trigger one calendar sync. If
tokens decrypt, the key is right. If you see
`IllegalStateException: Token decryption failed`, **stop** — you have the wrong
key, and reconnecting users is not the fix.

---

## 5. Point the app at the restored database — and the Flyway trap

```bash
# Update PRODUCTION_ENV_FILE (GitHub `production` environment secret):
SPRING_DATASOURCE_URL=jdbc:postgresql://<new-host>:5432/bunnycal?sslmode=verify-full
SPRING_DATASOURCE_USERNAME=bunnycal
SPRING_DATASOURCE_PASSWORD=<password>
```

**How Flyway behaves on a restored database.** The dump already contains
`flyway_schema_history` with all 143 rows.

- `baseline-on-migrate` is **irrelevant** — it only fires when no history table
  exists. Nothing is re-baselined.
- `validate-on-migrate` compares the 143 classpath migrations against the
  restored rows. Same app image → checksums match → **zero migrations applied.**
  This is the correct, safe path.
- Only versions above 142 are applied.

> ### ⚠️ The rollback trap
>
> Restoring an **older** dump under a **newer** app image is fine — Flyway
> applies the delta.
>
> Restoring a **newer** dump under an **older** app image **fails startup**:
> Flyway sees applied versions it does not know about and `validate-on-migrate`
> refuses to proceed.
>
> **Never roll the app image back past the migration level of the restored dump.**
> `deploy-prod.yml` accepts arbitrary `sha-` tags for rollback, so this is easy
> to do by accident during an incident.
>
> There are **no down-migrations**. Rollback is restore-from-dump only.

---

## 6. Post-restore reconciliation

### Clear stale scheduler locks — always do this

A lock held at dump time restores with a future `lock_until`, delaying the first
run of every scheduled job (including watch-channel renewal) by up to
`lockAtMostFor`.

```sql
DELETE FROM shedlock WHERE lock_until > now();
```

### If the API domain is unchanged — mostly self-healing

- **Google watch channels / Microsoft subscriptions** restore stale, but
  `GoogleWatchChannelRenewalScheduler` and `MicrosoftWatchChannelRenewalScheduler`
  run every 15 minutes and re-register. Worst case ~15 minutes of missed push
  notifications; `CalendarSyncScheduler` polls as a backstop, so nothing is lost,
  only delayed.
- **Billing** self-heals via the 15-minute reconcile cron, which re-reads
  provider state for stale subscriptions and open checkout attempts.
- **`outbox_events` is at-least-once** — expect a small number of duplicate
  notification emails for events near the restore point. Acceptable.
- **Bookings created after the restore point are gone**, but their calendar
  invites and confirmation emails already went out. See §7.

### If the API domain changed — manual work required

1. Update `GOOGLE_WEBHOOK` and `MICROSOFT_WEBHOOK`.
2. Update OAuth redirect URIs in **all three** provider consoles
   (Google Cloud, Azure App Registration, Zoom) — see `DEPLOYMENT.md` §8.
   Sign-in and calendar reconnects break until this is done.
3. Re-point webhook endpoints in the Stripe, Dodo, and Zoom dashboards. Note
   that recreating an endpoint regenerates its signing secret — update
   `STRIPE_WEBHOOK_SECRET`, `DODO_WEBHOOK_SECRET`,
   `COMMERCE_STRIPE_WEBHOOK_SECRET` accordingly.
4. Force watch-channel re-registration:
   ```sql
   UPDATE calendar_connections
      SET webhook_channel_id = NULL,
          webhook_resource_id = NULL,
          webhook_channel_expires_at = NULL;
   ```

---

## 7. Reconciling bookings lost to the RPO gap

This is the part with no automated fix, and it matters more here than the raw
data loss does.

A booking that existed at crash time but not at the restore point **already had
external side effects**: the Google/Microsoft calendar invite was created, the
confirmation email was sent, and a payment may have been captured. Attendees hold
invites to meetings the database no longer knows about, and the cancel/reschedule
links in those emails will 404.

There is no provider→BunnyCal reverse lookup for bookings whose IDs you no longer
have. Practical mitigation:

1. Determine the gap: restore point → incident time.
2. Check the payment providers for charges in that window
   (Stripe/Dodo dashboards) — these are your best record of lost bookings.
3. Check `logs/business.log` on the app VM if it survived; booking confirmations
   are logged there.
4. Contact affected hosts directly and let them re-confirm with their guests.

**This is the reason to prefer provider PITR (RPO seconds) over the nightly dump
(RPO ≤24 h) whenever the provider is available.**

---

## 8. Moving to a different cloud provider

The nightly dump is the migration mechanism — it restores into any PG16+.

1. Provision Postgres at the new provider, **same region as the app VM.** Every
   booking write makes several round trips (`SELECT ... FOR UPDATE` plus
   partition `EXCLUDE` checks), so cross-region latency multiplies. Co-location
   is not optional.
2. Restore per §3, verify per §4.
3. Write `.env` from the password manager — **the same**
   `CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64` and the other fail-fast secrets.
4. Repoint `SPRING_DATASOURCE_URL` (keep `sslmode=verify-full`) and redeploy.
5. Keep `api.bunnycal.io` pointing at the same place and §6's domain-change work
   does not apply.

---

## 9. Major version upgrades (PG16 → PG17)

`pg_upgrade` is awkward in Docker; use the dump instead.

```bash
# Restore the nightly dump into a 17 instance on a spare port, verify per §4,
# boot the app against it, then cut over in a maintenance window.
```

Downtime is a final dump plus restore — roughly 10–20 minutes at current data
size. `pgcrypto` and `btree_gist` ship in every `postgres:N-alpine`, so
extensions will not block the upgrade.

---

## 10. Monthly drill

A backup that has never been restored is hope, not a backup. Once a month:

1. Restore the latest dump into Docker (§3).
2. Run the verification SQL (§4).
3. Boot the app against it and **make one real booking**.
4. Confirm the calendar invite is created.
5. `docker rm -f pg-restore`.

Twenty minutes. If it fails, you found out on a Tuesday afternoon instead of
during an outage.

---

## 11. Monitoring

Two external dead-man's-switches, no monitoring stack:

- **Backup switch** — `pg-dump-nightly.sh` pings `BACKUP_HEALTHCHECK_URL` only
  after every step succeeds. No ping within the grace period → email. A partial
  or failed backup can never look healthy.
- **Uptime probe** — `GET https://api.bunnycal.io/actuator/health` every minute.
  Catches JVM crash, VM offline, Spring failing to start, and **database
  unreachable** — the last being a gap the once-daily backup switch cannot see.

Check backup history on the VM:

```bash
systemctl list-timers bunnycal-backup.timer
journalctl -u bunnycal-backup --since '7 days ago'
```
