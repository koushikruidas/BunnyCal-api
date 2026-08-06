# BunnyCal Backend Deployment

## 0) Topology

A single Hetzner **CX43** (8 vCPU / 16 GB / 160 GB) in **hel1 (Helsinki)**,
running Ubuntu 26.04 LTS (`resolute`).

```
CX43  —  Helsinki (hel1)
  ├─ Docker
  │    bunnycal-api   mem_limit 6g      ← heap ~4.5 GB, observed use ~3 GB
  │    caddy          :80 + :443, TLS
  │    redis          cache only, 256 MB cap, no published ports
  ├─ PostgreSQL 17    on the HOST (PGDG apt), listen_addresses=localhost
  └─ pgBackRest       → AWS S3 eu-north-1 (Stockholm), a DIFFERENT vendor
```

**On the region.** The API previously ran in Ashburn; it now runs in the EU.
US users see roughly 110–200 ms on API calls, which is an accepted early-stage
cost trade-off — the CX tier is EU-only and materially cheaper than the CPX/CCX
equivalents available in Ashburn. The frontend is unaffected: it is served from
S3 + CloudFront, so US users still hit a nearby CDN edge and only API round
trips pay the latency.

**GDPR.** The database holds EU user PII and encrypted OAuth tokens. Both backup
layers therefore stay in the EU (`eu-north-1`, the closest AWS region to hel1).
Pointing either at a US bucket would create an international personal-data
transfer requiring a documented lawful basis — avoid it.

**Postgres runs on the host, not in Compose and not managed.** Installing it on
the host keeps pgBackRest's filesystem access to the data directory
straightforward and keeps the restore path simple. We own backups, PITR, and
patching — see §9.

The app reaches it at **`172.30.0.1`** — the gateway of the pinned `bunnycal`
network in `docker-compose.yaml`, which is the host's own address on that
bridge. `SPRING_DATASOURCE_URL` carries **`sslmode=disable`**, which is correct
here: the connection never leaves the machine, and the local server has no
certificate for `verify-full` to check.

Not `host.docker.internal`. With `extra_hosts: host-gateway` that name resolves
to the **default** bridge gateway (`172.17.0.1`) whatever network the container
is attached to — so on this stack it points at an interface Postgres does not
listen on, and connections are refused at the TCP layer before authentication.

Three settings are coupled and must change together:

| Setting | Location |
|---|---|
| `subnet: 172.30.0.0/24` | `docker-compose.yaml` |
| `listen_addresses = '127.0.0.1,172.30.0.1'` | `/etc/postgresql/17/main/conf.d/10-bunnycal.conf` |
| `host bunnycal bunnycal 172.30.0.0/24` | `/etc/postgresql/17/main/pg_hba.conf` |

> ⚠️ If the database ever moves off this host, `sslmode` must go back to
> `verify-full` in the same change. Do **not** "restore" `verify-full` while the
> database is local — boot will fail. Keep the app and database in the same
> region if they are ever split: a booking write makes several round trips
> (`SELECT ... FOR UPDATE` plus partition `EXCLUDE` checks), so latency is
> multiplied rather than merely added.

**Why one VM.** With a single API instance, splitting the database onto its own
box buys no availability — either machine failing is an outage regardless — while
adding a private network, a second host to patch, and a network hop on every
booking write. What makes one VM safe is that backups live at a **different
vendor**, so losing the box entirely is still a ~5-minute-RPO recovery. Splitting
Postgres out later is a few hours of work using
[`docs/runbooks/vm-provisioning.md`](docs/runbooks/vm-provisioning.md).

**Memory budget.** `Dockerfile` sets `-XX:MaxRAMPercentage=75`, which sizes the
heap against whatever the container can see. The `mem_limit: 6g` in
`docker-compose.yaml` is therefore **load-bearing** — without it the JVM claims
~12 GB of the 16 GB and starves Postgres.

| Component | Committed |
|---|---|
| `bunnycal-api` (`mem_limit: 6g`) | 6.00 GB (heap ~4.5 GB; observed use ~3 GB) |
| PostgreSQL `shared_buffers` | 2.00 GB |
| PostgreSQL `work_mem` × 30 Hikari connections | ~0.47 GB worst case |
| Redis (capped) | 0.25 GB |
| Caddy + Docker + OS | ~1.00 GB |
| **Free for OS page cache** | **~6.3 GB** |

During a `CREATE INDEX`, `maintenance_work_mem` × `max_parallel_maintenance_workers`
adds a transient 2 GB, still leaving ~4.3 GB cached.

**These numbers are sized against the application, not the machine**, and that is
deliberate:

- **Postgres is under the usual "25% of RAM" rule** because that rule assumes a
  *dedicated* database server. Postgres double-caches (a page in `shared_buffers`
  is usually also in the page cache), and the page cache is elastic where
  `shared_buffers` is pinned. Larger buffers also mean more dirty pages per
  checkpoint, which matters on a host that is also serving JVM I/O. The working
  set today is well under 1 GB.
- **The API is above its observed usage** because the observed figure is
  steady-state. Flyway applying 143 migrations on first boot is the memory peak,
  and G1 collects less efficiently as a heap nears its ceiling — a tight cap
  costs GC time long before it costs an OOM.
- **`effective_cache_size = 10GB` allocates nothing.** It is a planner hint
  describing `shared_buffers` + page cache. Setting it lower does not free
  memory; it only makes the planner underestimate caching and prefer sequential
  scans where an index would win.

Tuning order under memory pressure: **`work_mem` first** (per sort node, so it
multiplies), then the API cap. `shared_buffers` is last — and note it needs a
restart, whereas `effective_cache_size` is only a reload.

Add a 2 GB swapfile as OOM insurance.

**CPU.** 8 vCPU, so Postgres is configured for real parallelism
(`max_parallel_workers=8`, `max_parallel_workers_per_gather=4`) and pgBackRest
runs `process-max=4`. Defaults sized for a 2-core box would leave most of the
machine idle.

**Redis is never backed up.** It holds only TTL'd cache and every call site
fails open to Postgres; a wipe costs about 60 seconds of slower slot lookups.
It runs with persistence disabled, `requirepass` set, and no published ports.
Distributed locking is ShedLock on JDBC, not Redis.

**One API instance only.** Ten of the 26 `@Scheduled` jobs have no `@SchedulerLock`
— including `OutboxWorker`, `BookingExpiryScheduler`, and `AccountDeletionWorker`,
several of which send mail or expire bookings. A second replica would double-run
them. That, not session state, is what blocks horizontal scaling today.

## 1) One-time VM setup (Hetzner)

Full step-by-step, from a stock image:
**[`docs/runbooks/vm-provisioning.md`](docs/runbooks/vm-provisioning.md)**.

Summary:
- Create a **CX43 in hel1 running Ubuntu 26.04 LTS** — plain Ubuntu, *not* Hetzner's
  Docker app image. Postgres runs on the host here, so the box is not a pure
  Docker host, and provisioning it ourselves keeps the runbook valid from a
  stock image.
- Cloud firewall: allow **80, 443, SSH** and nothing else. Postgres binds to
  `localhost` and Redis publishes no ports, so neither is reachable off-box by
  construction rather than by firewall rule.
- Install Docker Engine + Compose plugin from `download.docker.com`.
- Install PostgreSQL 17 from the PGDG apt repo; apply
  `deploy/postgres/postgresql.conf.tuned` and `pg_hba.conf.example`.
- Install pgBackRest, configure `/etc/pgbackrest/pgbackrest.conf` from
  `deploy/pgbackrest/pgbackrest.conf.example`, then `stanza-create` and take a
  first full backup — see §9.
- Clone this repo, create `.env` from `.env.example`, fill all secrets, set
  `APP_DOMAIN`, and set `REDIS_PASSWORD` (Compose refuses to start without it).
- Point the DNS A record at the VM **before** first boot, so Caddy can complete
  the ACME challenge.
- Add a 2 GB swapfile.

## 2) Local verification
- Build image: `docker build .`
- Start stack: `docker compose up -d`
- Check health: `curl http://localhost/actuator/health`

## 3) CI image publishing (`.github/workflows/ci-image.yml`)
Releases are intentional — not every merge ships. Two modes:

- **Push to `main`** (or manual `workflow_dispatch`): runs tests, builds the
  Docker image, and pushes ONLY an immutable short-SHA tag
  (`banical-cals/bunnycal-api:<sha>`). No semantic version, no `latest`. This is
  a validated CI artifact, not a release.
- **Push a release tag `vX.Y.Z`** (`git tag v1.0.0 && git push origin v1.0.0`):
  runs tests, validates the version via `scripts/validate-release-version.sh`
  (rejects `-SNAPSHOT` / `dev` / non-semver), then pushes:
  - `banical-cals/bunnycal-api:1.0.0`  (semantic version — source of truth: the git tag)
  - `banical-cals/bunnycal-api:latest`
  - `banical-cals/bunnycal-api:<sha>`  (immutable, for rollback / deterministic deploys)

The workflow fails — and publishes nothing — if tests fail, version validation
fails, the Docker build fails, or the push fails. Image promotion to production
is a separate manual step (no CD in this workflow yet).

- Build uses Buildx (`linux/amd64`), Gradle dependency cache, and GitHub Actions
  Docker layer cache.
- Required GitHub secrets (use a Docker Hub **access token**, not a password):
  - `DOCKERHUB_USERNAME`
  - `DOCKERHUB_TOKEN`
- Docker Hub repo is set via the `IMAGE_REPO` env in the workflow
  (`banical-cals/bunnycal-api`) — change it there if the final org name differs.

## 4) Production deployment
Every successful `ci-image` run on `main` deploys its immutable short-SHA image
to production. Protect the GitHub `production` environment with required
reviewers if releases need an approval gate. A manual `deploy-prod` dispatch
accepts a SHA tag only and is reserved for rollbacks.

The workflow pulls and recreates only `bunnycal-api`; it does not run `docker
compose down`, so Redis, Caddy, networks, and volumes remain online. (Postgres
runs on the host outside Compose entirely, so a deploy never restarts it — that
separation is deliberate now that the database shares this box.) It validates
Compose configuration and the public health endpoint before reporting success.

> ### ⚠️ Rollback trap: never roll back past a restored dump's migration level
>
> Migrations run automatically at boot with `validate-on-migrate: true`, and
> **there are no down-migrations** — rollback is restore-from-dump only.
>
> - Older database + newer image → fine, Flyway applies the delta.
> - **Newer database + older image → startup FAILS.** Flyway finds applied
>   versions it does not know about and refuses to proceed.
>
> Because `deploy-prod` accepts any `sha-` tag, it is easy to roll the image
> back past the schema during an incident and turn one outage into two. Check
> `SELECT max(version::numeric) FROM flyway_schema_history;` against the target
> image's migration set before rolling back.
>
> Full detail in [`docs/runbooks/disaster-recovery.md`](docs/runbooks/disaster-recovery.md) §5.

Required GitHub secrets:
  - `HETZNER_HOST`
  - `HETZNER_USER`
  - `HETZNER_SSH_KEY`
  - `HETZNER_DEPLOY_PATH`
  - `DOCKERHUB_USERNAME`
  - `DOCKERHUB_TOKEN`
  - `APP_DOMAIN`
  - `PRODUCTION_ENV_FILE` — complete contents of the production `.env` file.
    Store this as a multi-line secret in the GitHub `production` environment,
    not in repository secrets.

`PRODUCTION_ENV_FILE` is the production configuration source of truth. When a
new secret or setting is added to local `.env.prod`, update this one GitHub
environment secret; the deployment writes it atomically to the VM before it
sets `BUNNYCAL_IMAGE`. Never commit `.env.prod` or the VM `.env`.

## 5) Image retention
- After each healthy deployment, the VM removes Docker images that are unused
  for more than seven days. The image used by a running container is never
  pruned.
- `.github/workflows/cleanup-dockerhub.yml` runs weekly and keeps the newest
  ten `sha-*` tags in Docker Hub. It never deletes `latest` or semantic version
  tags. A manual dispatch defaults to dry-run mode so deletion candidates can
  be reviewed first.
- The Docker Hub token must have permission to delete repository tags.

## 6) Production hardening notes
- `.env` is the single source of truth and is git-ignored. Never commit it.
- The `prod` Spring profile fails fast: it refuses to boot if `JWT_SECRET`,
  `CALENDAR_WEBHOOK_SHARED_SECRET`, `CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64`,
  `CALENDAR_OAUTH_STATE_SECRET`, `GOOGLE_CLIENT_ID/SECRET`, the SES SMTP
  credentials, or the DB credentials are missing. No insecure defaults exist.
- `docker compose up` also fails fast if `BUNNYCAL_IMAGE`, `REDIS_PASSWORD`, or
  any of `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` is unset.
- Neither Postgres nor Redis is reachable off-box. Postgres binds to
  `localhost` only (`listen_addresses` in `postgresql.conf`) and authenticates
  with `scram-sha-256`; Redis publishes no ports and is reachable only from
  `bunnycal-api` on the Compose network, with a password required. Both are
  closed by construction, not merely by firewall rule.
- Caddy exposes ONLY `/actuator/health` and the public API paths. Metrics,
  `/actuator/prometheus`, and all other actuator endpoints are 404 from the
  internet and only reachable on the internal Docker network.
- Prometheus (9090) and Grafana (3000) bind to `127.0.0.1` on the VM — not the
  public internet. Reach them via SSH tunnel:
  - `ssh -L 9090:127.0.0.1:9090 <user>@<vm>` then open http://localhost:9090
  - `ssh -L 3000:127.0.0.1:3000 <user>@<vm>` then open http://localhost:3000

## 7) Secret rotation
The OAuth (Google/Microsoft/Zoom) and AWS SES credentials currently in `.env`
were migrated from a local developer machine. Rotate them before/at go-live and
update `.env` on the VM. After rotating, restart with `docker compose up -d`.

## 8) OAuth provider callback URLs to register
Ensure each provider's console has the production redirect URI whitelisted:
- Google:    `https://api.bunnycal.io/integrations/calendar/google/callback`
- Microsoft: `https://api.bunnycal.io/integrations/calendar/microsoft/callback`
- Zoom:      `https://api.bunnycal.io/integrations/conferencing/zoom/callback`

## 9) Backups

Two layers, deliberately covering different failures:

| Layer | Mechanism | Covers | RPO |
|---|---|---|---|
| **pgBackRest** | physical base backups + continuous WAL archive | disk failure, bad migration, accidental delete, **total VM loss** | **~5 min** |
| **Nightly `pg_dump`** | encrypted logical dump, off-site | major-version upgrade, provider migration, **corrupt pgBackRest repo** | ≤ 24 h |

Neither replaces the other, and the distinction is not cosmetic:

**A logical `pg_dump` cannot be the base for WAL replay.** WAL records reference
physical block addresses inside data files, so replay requires a *physical* base
backup. Per the PostgreSQL manual, dumps "cannot be used as part of a
continuous-archiving solution." The 5-minute RPO comes entirely from pgBackRest.

The dump's value is that it is **format-independent**: a pgBackRest repository is
tied to the PostgreSQL major version and to pgBackRest itself, whereas a
`pg_dump -Fc` archive restores into any PG16+ anywhere. It is the
major-version-upgrade mechanism, the provider-migration mechanism, and the only
thing that survives losing the repository passphrase.

Both must live with a **different vendor** than the VM. Same-vendor backups do
not survive a billing lockout, a compromised credential, or account suspension.

### Install the pgBackRest timers

Configuration lives in `/etc/pgbackrest/pgbackrest.conf` (template:
`deploy/pgbackrest/pgbackrest.conf.example`), **not** in `.env` — these units run
as `postgres`, which must not be able to read the application's secrets.

```bash
sudo cp deploy/systemd/pgbackrest-*.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now pgbackrest-full.timer pgbackrest-diff.timer pgbackrest-check.timer

# Always run the first full backup by hand and read the output:
sudo systemctl start pgbackrest-full.service
journalctl -u pgbackrest-full -f
```

| Unit | Schedule | Purpose |
|---|---|---|
| `pgbackrest-full.timer` | Sun 02:00 | full physical base backup |
| `pgbackrest-diff.timer` | Mon–Sat 02:00 | differential since the last full |
| `pgbackrest-check.timer` | hourly | **verifies WAL is actually arriving** |
| `bunnycal-backup.timer` | daily 03:00 | logical dump, off-site |

The hourly `check` matters more than it looks. A broken `archive_command` — bad
credentials, a full spool directory, an expired storage key — is completely
silent: Postgres keeps serving traffic and the backups keep looking fine, while
the PITR guarantee is quietly false. The check forces a WAL segment switch and
confirms it lands in the repository.

`archive_timeout = 300` in `deploy/postgres/postgresql.conf.tuned` is what
actually delivers the 5-minute RPO. Without it, a quiet database may not fill a
16 MB segment for hours, and un-archived WAL is unrecoverable WAL.

### Install the nightly dump timer

```bash
sudo mkdir -p /var/backups/bunnycal && sudo chown bunnycal:bunnycal /var/backups/bunnycal
sudo cp deploy/systemd/bunnycal-backup.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now bunnycal-backup.timer

# Always run the first one by hand and read the output:
sudo systemctl start bunnycal-backup.service
journalctl -u bunnycal-backup -f
```

Configuration lives in the `Backups` section of `.env` (see `.env.example`):
`BACKUP_DATABASE_URL`, `BACKUP_AGE_PUBLIC_KEY`, `BACKUP_RCLONE_REMOTE`,
`BACKUP_LOCAL_DIR`, `BACKUP_RETAIN_DAYS`, `BACKUP_HEALTHCHECK_URL`.

Set `BACKUP_AGE_PRIVATE_KEY_FILE` too if the private key is available on the
VM — the script then verifies each dump actually decrypts and that
`pg_restore` can read it, which catches a wrong age key on the night it breaks
rather than during an outage.

### ⚠️ The pgBackRest passphrase is unrecoverable

`repo1-cipher-pass` **cannot be changed or recovered after `stanza-create`**.
Lose it and every backup in that repository is permanently unreadable. Store it
in a password manager **and** on paper, alongside
`CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64` (see §1 of the DR runbook).

### ⚠️ The encryption key is part of the backup

`CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64` encrypts every calendar/Zoom OAuth
refresh token. **A restored database with a different key is unrecoverable**:
every user must reconnect their calendar, and it fails *silently* until the
first sync. There is no key versioning, so there is no fallback key.

It must live in a password manager **and** on paper — not only in the VM's
`.env` and the GitHub `PRODUCTION_ENV_FILE` secret, since GitHub secrets are
write-only and cannot be read back to verify. Same for `JWT_SECRET`,
`CALENDAR_WEBHOOK_SHARED_SECRET`, `CALENDAR_OAUTH_STATE_SECRET`, and
`APP_EMBED_TOKEN_SECRET`.

### Monitoring

No monitoring stack. Three external dead-man's-switches plus a disk alert:

- **Uptime** — probe `https://api.bunnycal.io/actuator/health` every minute.
  Catches JVM crash, VM offline, Spring startup failure, and database
  unreachable. (Caddy already exposes only this actuator endpoint publicly.)
- **Logical backup** — the script pings `BACKUP_HEALTHCHECK_URL` only after
  every step succeeds, so a partial backup can never look healthy.
- **pgBackRest archive** — `pgbackrest-check.service` pings
  `PGBACKREST_HEALTHCHECK_URL` (in `/etc/pgbackrest/healthcheck.env`) only after
  the hourly `check` passes. This is the one that catches a silently broken WAL
  archive.
- **Disk space** — alert at 80% on `/`. A full disk stops WAL archiving, and
  Postgres will refuse writes rather than lose WAL. On a box shared with the JVM
  and its logs, this is the most likely way the setup breaks.

### Restoring

See [`docs/runbooks/disaster-recovery.md`](docs/runbooks/disaster-recovery.md).
Restore drills are monthly and manual — a backup that has never been restored
is hope, not a backup.
