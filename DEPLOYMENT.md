# BunnyCal Backend Deployment

## 1) One-time VM setup (Hetzner)
- Install Docker Engine + Docker Compose plugin.
- Clone this repo on the VM.
- Create `.env` from `.env.example` and fill all secrets.
- Set `APP_DOMAIN` to the production API domain.
- Ensure DNS A record points to the Hetzner VM.

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
compose down`, so Postgres, Redis, Caddy, networks, and volumes remain online.
It validates Compose configuration and the public health endpoint before
reporting success.

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
- `docker compose up` also fails fast if `GRAFANA_ADMIN_PASSWORD` is unset.
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
