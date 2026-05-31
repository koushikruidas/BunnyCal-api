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

## 3) CI image publishing
- On pushes to `main`, GitHub Actions runs tests and publishes image to Docker Hub.
- Required GitHub secrets:
  - `DOCKERHUB_USERNAME`
  - `DOCKERHUB_TOKEN`

## 4) Production deployment
- Trigger `deploy-prod` workflow manually and provide image tag (example: `sha-<commit>`).
- Required GitHub secrets:
  - `HETZNER_HOST`
  - `HETZNER_USER`
  - `HETZNER_SSH_KEY`
  - `HETZNER_DEPLOY_PATH`
  - `DOCKERHUB_USERNAME`
  - `DOCKERHUB_TOKEN`
  - `APP_DOMAIN`

The workflow updates `BUNNYCAL_IMAGE` in VM `.env`, pulls the image, restarts compose services, then verifies the health endpoint.
