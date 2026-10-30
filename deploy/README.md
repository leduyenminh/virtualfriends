# Deploying VirtualFriends

Everything runs as containers configured by environment variables, so no cloud vendor is tied in.

| Piece | What | Where it can come from |
|---|---|---|
| `vchat-web` image | React build served by Caddy, which also proxies `/api`, `/ws`, `/graphql`, `/oauth2`, `/files` to the backend (one origin, no CORS), adds security headers, and can get HTTPS certificates itself | `frontend/Dockerfile` |
| `vchat-backend` image | Spring Boot API + WebSocket on port 8080; health at `/actuator/health/liveness` and `/readiness` | `backend/Dockerfile` |
| PostgreSQL 16 | Data (Flyway migrates on start) | The compose file below, or Neon, Supabase, Aiven, your cloud's managed Postgres |
| Redis 7 | Cache, presence, rate limits, token revocation | The compose file below, or Upstash, Redis Cloud, Aiven (set `REDIS_PASSWORD`, usually `REDIS_SSL=true`) |
| Uploads | Stickers, attachments, avatar models | A volume (`STORAGE_MODE=LOCAL`, default), or any S3-compatible store: Cloudflare R2, Backblaze B2, MinIO, ... |

CI (`.github/workflows/ci.yml`) pushes both images for amd64 and arm64 to `ghcr.io/<owner>/vchat-backend` and
`vchat-web` on every push to `main`. Set the optional `DEPLOY_HOOK_URL` secret to have it ping your host afterwards.

## Option 1: one VM with docker compose (simplest, cheapest)

Any Linux machine with Docker and about 2 GB of RAM: a free-tier VM (Oracle Cloud Always Free Ampere), a small VPS
(Hetzner, DigitalOcean, Linode, Vultr), or a home server. Point a DNS name at it and open ports 80 and 443.

```bash
git clone https://github.com/leduyenminh/virtualfriends.git && cd virtualfriends
```

```bash
cp deploy/.env.example deploy/.env
```

Fill in `deploy/.env` (domain, `JWT_SECRET`, `DB_PASSWORD`, API keys), then:

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

Caddy fetches a Let's Encrypt certificate for `SITE_ADDRESS` on first start. To use the CI images instead of
building on the VM, set `IMAGE_PREFIX=ghcr.io/<owner>/vchat` in `.env` and run `docker compose ... pull` before `up -d`.

No public IP (home server, or a VM behind NAT)? Set `SITE_ADDRESS=:80` and publish it with Cloudflare Tunnel, which
also terminates TLS.

Day-to-day:

| Task | Command (from the repo folder) |
|---|---|
| Update | `git pull`, then the `up -d --build` command again |
| Logs (JSON, one event per line) | `docker compose -f deploy/docker-compose.yml logs -f backend` |
| Database backup | `docker compose -f deploy/docker-compose.yml exec db pg_dump -U vchat -Fc vchat > vchat.dump` |
| Slow queries | `docker compose -f deploy/docker-compose.yml logs db` (statements over 500 ms, plans over 1 s) |

Back up the `dbdata` and `uploads` volumes; `caddy_data` holds the certificates.

## Option 2: Kubernetes

Works on any cluster (k3s on a VM, GKE, AKS, DigitalOcean, Linode, Oracle OKE, Civo). The manifests in `k8s/` run the
web and backend images behind an Ingress; bring PostgreSQL and Redis as managed services or Helm releases.

```bash
cp deploy/k8s/secrets.env.example deploy/k8s/secrets.env
```

Fill in `secrets.env`, set your domain in `config.env` and `ingress.yaml`, check the image names in
`kustomization.yaml`, then:

```bash
kubectl apply -k deploy/k8s
```

Uploads default to a 5 GiB volume. Switch to S3-compatible storage (see `config.env`) before running more than one
backend replica or if your cluster has no default StorageClass.

## Option 3: a container platform

Render, Railway, Fly.io, Koyeb, Northflank, Google Cloud Run, Azure Container Apps and similar can run the two images
as two services:

- **backend:** `SPRING_PROFILES_ACTIVE=prod` plus the variables in `k8s/config.env` and `k8s/secrets.env.example`.
  Keep exactly **one** always-on instance (no scale to zero): the WebSocket broker lives in memory.
  Use S3-compatible storage, since platform disks are usually wiped on redeploy.
- **web:** `SITE_ADDRESS=:8080` (or `:$PORT` if the platform assigns one) and `API_UPSTREAM=<backend's private
  host:port>`. The platform must support WebSockets and allow 50 MB request bodies.
- Register `https://<your domain>/login/oauth2/code/google` (and `/facebook`) with the sign-in providers.

## Object storage notes

With `STORAGE_MODE=S3` the backend writes with the standard S3 API (works with any S3-compatible provider, no AWS
account involved) and returns links under `MEDIA_BASE_URL`. The bucket must be publicly readable at that URL. Ideally
use a different domain from the app (for example `media.example.com` on R2), so uploaded SVG/HTML can never run on
the app's origin, and allow `GET` from any origin in the bucket's CORS rules so 3D/Live2D models load.
