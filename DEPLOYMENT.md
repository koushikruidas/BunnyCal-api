# BunnyCal Backend Deployment

## 0) Topology

```
App VM (US East)                     Managed Postgres (us-east-1)
  bunnycal-api                         automated backups + PITR
  redis   (cache only, no backup)      provider-operated
  caddy   (TLS termination)
```

**Postgres is NOT in Compose.** It runs on a managed provider so that backups,
PITR, patching, and failover are the provider's responsibility rather than
ours. The app reaches it via `SPRING_DATASOURCE_URL`, which **must** include
`sslmode=verify-full` — unlike the old setup, this connection leaves the host
and crosses the public internet.

**Keep the app VM and the database in the same region.** A booking write makes
several round trips (`SELECT ... FOR UPDATE` plus partition `EXCLUDE` checks),
so cross-region latency is multiplied, not merely added.

**Redis is never backed up.** It holds only TTL'd cache and every call site
fails open to Postgres; a wipe costs about 60 seconds of slower slot lookups.
It runs with persistence disabled, `requirepass` set, and no published ports.

## 1) One-time VM setup (Hetzner)
- Install Docker Engine + Docker Compose plugin.
- Clone this repo on the VM.
- Create `.env` from `.env.example` and fill all secrets.
- Set `APP_DOMAIN` to the production API domain.
- Ensure DNS A record points to the Hetzner VM.
- Provision managed Postgres, enable **automated backups and PITR**, and set
  `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`.
- Set `REDIS_PASSWORD` (Compose refuses to start without it).
- Install the backup timer — see §9.

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
is managed and external, so it is unaffected by deploys entirely.) It validates
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
- Neither Postgres nor Redis publishes a host port. Postgres is managed and
  external; Redis is reachable only from `bunnycal-api` on the Compose network
  and requires a password.
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

| Layer | Covers | RPO |
|---|---|---|
| Managed provider backups + PITR | disk failure, bad migration, accidental delete | seconds–minutes |
| Nightly encrypted `pg_dump`, off-site | **losing the provider account**, cloud migration, major-version upgrade | ≤ 24 h |

The second layer is not redundant. Provider backups are **tied to the
instance** — delete it and they are deleted too — so they do not protect
against a billing lockout, a compromised credential, or account suspension.
For that reason the dump must live with a **different vendor** than the
database.

### Install the backup timer

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

No monitoring stack. Two external dead-man's-switches:

- **Backup** — the script pings `BACKUP_HEALTHCHECK_URL` only after every step
  succeeds, so a partial backup can never look healthy. No ping → email.
- **Uptime** — probe `https://api.bunnycal.io/actuator/health` every minute.
  Catches JVM crash, VM offline, Spring startup failure, and database
  unreachable. (Caddy already exposes only this actuator endpoint publicly.)

### Restoring

See [`docs/runbooks/disaster-recovery.md`](docs/runbooks/disaster-recovery.md).
Restore drills are monthly and manual — a backup that has never been restored
is hope, not a backup.
