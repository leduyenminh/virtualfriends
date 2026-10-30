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

No public IP (home server, or a VM behind NAT), or rather not open ports? Publish it through
[Cloudflare Tunnel](#cloudflare-tunnel-https-and-r2-free) instead.

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

## Cloudflare: Tunnel, HTTPS and R2 (free)

Cloudflare can sit in front of option 1 at no cost: HTTPS, CDN and DDoS protection without opening any port (so it
also works from a home PC), plus R2 for uploads. It can't run PostgreSQL or Redis; those stay in the compose stack.
Do these steps in the dashboard (dash.cloudflare.com). Menu names move around now and then; the dashboard's search
finds them.

1. **Domain.** *Websites → Add a domain*, then switch the nameservers at your registrar (or buy a domain under
   *Domain Registration*). Quick tunnels on `trycloudflare.com` are for testing only: their address changes on every
   start, which breaks the sign-in redirect URIs.
2. **Tunnel.** *Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared*, name it `vchat`. Copy the token
   from the install command it shows (the long string after `--token`); don't run that command. Add a public
   hostname (called a *route* in newer dashboards): `chat.yourdomain.com` → service **HTTP**, URL **`web:80`**.
3. **`.env`.** Use the *Cloudflare Tunnel* block of `.env.example`: `SITE_ADDRESS=:80`,
   `APP_URL=https://chat.yourdomain.com`, the ports bound to `127.0.0.1`, `TUNNEL_TOKEN`, and
   `CLIENT_IP_HEADERS=Cf-Connecting-Ip` (otherwise per-IP rate limits see addresses a client can fake). Then start:

   ```bash
   docker compose -f deploy/docker-compose.yml --profile tunnel up -d --build
   ```

   `docker compose -f deploy/docker-compose.yml logs cloudflared` should show `Registered tunnel connection`.
4. **Domain settings.** The app's Content-Security-Policy blocks scripts Cloudflare injects, so turn these off:
   *Rocket Loader* (it rewrites the app's script tags; the app may not load), *Email Address Obfuscation*, and
   automatic *Web Analytics*. Turn *Always Use HTTPS* on.
5. **Sign-in providers.** Register `https://chat.yourdomain.com/login/oauth2/code/google` (and `/facebook`).

Good to know: the free plan accepts uploads up to 100 MB (the app's limit is 50 MB), and Cloudflare closes a
WebSocket after ~100 s of silence, which the backend's 20 s STOMP heart-beats prevent. On Kubernetes, run `cloudflared`
as a Deployment with the same token and route the hostname to `http://web.vchat.svc:80`.

### R2 for uploads (optional)

On one machine the `uploads` volume is fine. R2 (10 GB, a million uploads and ten million downloads a month free, no
download fees) takes the files off the server. Cloudflare asks for a card or PayPal before enabling R2, even though
usage within the free tier costs nothing. Switch before you have real users: files already on the volume aren't
copied, and their `/files/...` links stop working in S3 mode.

1. *R2 Object Storage → Create bucket* `vchat-media`.
2. Bucket *Settings → Custom Domains → Connect* `media.yourdomain.com` (the free `r2.dev` address is rate-limited and
   meant for testing). A different hostname from the app keeps uploaded SVG/HTML away from the app's origin.
3. Bucket *Settings → CORS policy*. `*` because Unity avatars load from a sandboxed iframe whose origin is `null`:

   ```json
   [{ "AllowedOrigins": ["*"], "AllowedMethods": ["GET", "HEAD"], "AllowedHeaders": ["*"], "MaxAgeSeconds": 86400 }]
   ```

4. *R2 → Manage API tokens → Create API token*: **Object Read & Write**, applied to `vchat-media` only. Copy the
   Access Key ID and Secret Access Key (shown once). The account ID is on the R2 overview page.
5. In `.env`: `STORAGE_MODE=S3`, `STORAGE_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com`,
   `STORAGE_REGION=auto`, `STORAGE_BUCKET=vchat-media`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`,
   `MEDIA_BASE_URL=https://media.yourdomain.com`. Restart with the same `up -d` command.
6. Optional: *Rules → Transform Rules → Modify response header* for `media.yourdomain.com`, set
   `X-Content-Type-Options: nosniff`.

## Object storage notes

With `STORAGE_MODE=S3` the backend writes with the standard S3 API (works with any S3-compatible provider, no AWS
account involved) and returns links under `MEDIA_BASE_URL`. The bucket must be publicly readable at that URL. Ideally
use a different domain from the app (for example `media.example.com` on R2), so uploaded SVG/HTML can never run on
the app's origin, and allow `GET` from any origin in the bucket's CORS rules so 3D/Live2D models load.
