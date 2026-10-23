# VirtualFriends (vchat)

WhatsApp-style chat with custom sticker packs and VTuber-style AI friends: a 3D/Live2D avatar, emotions, and voice.

| Layer | Tech |
|---|---|
| Backend | Spring Boot 3.3 (Java 21, virtual threads) · Hibernate/JPA · PostgreSQL 16 · Flyway · STOMP WebSocket |
| Security | Spring Security: 15-min JWT + rotating httpOnly refresh cookie · revocation · USER/ADMIN roles · Google & Facebook OAuth2 · CSP · Redis rate limiting |
| Caching | Redis (Spring Cache, presence, rate limits) · Ehcache (Hibernate 2nd-level cache) |
| Events | In-process by default · **Kafka** mode for durable bot jobs + multi-instance WebSocket fan-out |
| Cross-cutting | Spring AOP: service timing/metrics/slow-call log, `@RateLimited` · request-id access log |
| AI | Claude (`claude-opus-5-5`) via `anthropic-java` for the bot's replies |
| Voice | Browser Web Speech (free) · ElevenLabs · Google Cloud TTS · COEIROINK (local, Japanese) |
| Frontend | React 18 · Vite · Three.js / react-three-fiber · three-vrm · pixi-live2d-display |
| Avatars | Built-in stick figures · VRM · glTF/GLB (Blender) · Live2D Cubism 4 · Unity WebGL (sandboxed iframe) |
| AWS | CloudFront · S3 · ECS Fargate · ALB · RDS PostgreSQL · ElastiCache Redis · Secrets Manager · CloudWatch (CDK) |
| Themes | Light · Dark (WhatsApp-style) · Windows 95 · Frutiger Aero |

```
backend/        Spring Boot API + WebSocket
backend/perf/   DB tuning guide, seed data, EXPLAIN scripts and measured before/after plans
frontend/       React app
unity/          AvatarBridge.cs + WebGL template for Unity avatars
infra/          AWS CDK stack
design/         Clickable mock screens (open design/mockups.html; earlier styling, before the WhatsApp redesign)
```

## Features

- **Messaging:** 1:1 and group chats (＋ → New group), unread badges, All/Unread/Groups/AI-friend filters, pin and mute,
  typing indicators, ✓ sent / blue ✓✓ read ticks, online / last-seen presence, replies, reactions, edit and
  "delete for everyone" within 24h, photos/files, **voice notes** (🎤 to record, ➤ to send), emoji, infinite scroll through history.
- **Accounts:** username + password (usernames aren't case-sensitive), or Google / Facebook. You stay signed in across reloads
  for 30 days, can log out of all devices, and admins can moderate. The sign-in screen remembers your
  last username and says why you were signed out.
- **Stickers:** packs per user. Import PNG/SVG/GIF/WEBP from your device, **Google Drive** (Picker) or **OneDrive** (File
  Picker), or make one in the built-in sticker maker. SVGs are sanitized with DOMPurify.
- **AI friends:** character sheet, main language (vi/en/zh/ja/ko/es/ru), voice engine and voice, and an avatar. Claude starts
  each reply with an `[emotion:lang]` tag that sets the avatar's expression and picks the TTS language.
- **Offline history:** chats you open (plus the 20 most recent at startup) are saved in a SQLite database in the browser
  (`@sqlite.org/sqlite-wasm`, OPFS, `frontend/src/offline/`). Saved messages show instantly, stay readable when the server
  can't be reached (the production build also caches the app itself with a service worker), and can be searched from the
  sidebar, accent-insensitive, 3+ characters. Sending is paused while offline. The copy is erased at the sign-in screen.

## Run locally

Prerequisites: JDK 21, Maven, Node 20+, and Docker Desktop (running). Run each step from the `vchat` folder.
Windows PowerShell 5.1 doesn't support `&&`, so the steps below are written for PowerShell.

1. Database and Redis (terminal 1):

```powershell
docker compose up -d db redis
```

2. Backend (terminal 1). Set the Claude key once per terminal (needed only for AI-friend replies), then start:

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

```powershell
mvn -f backend/pom.xml spring-boot:run
```

3. Frontend (terminal 2, opened after installing Node so it's on PATH). Use `npm.cmd`: PowerShell's default
   execution policy blocks the `npm.ps1` wrapper with "running scripts is disabled on this system".

```powershell
npm.cmd install --prefix frontend
```

```powershell
npm.cmd run dev --prefix frontend
```

Open http://localhost:5173 and use **Create account**. Open a private window and create a second account to chat
between two users. To try the bot, open **＋ → New AI friend**.

### Optional settings (environment variables for the backend)

| Variable | Purpose |
|---|---|
| `ELEVENLABS_API_KEY`, `GOOGLE_TTS_API_KEY`, `COEIROINK_URL` | Server voice engines |
| `GOOGLE_CLIENT_ID` + `GOOGLE_CLIENT_SECRET` | "Continue with Google". In Google Cloud Console → Credentials → OAuth client (Web), authorized redirect URI: `http://localhost:5173/login/oauth2/code/google` |
| `FACEBOOK_CLIENT_ID` + `FACEBOOK_CLIENT_SECRET` | "Continue with Facebook". In Meta for Developers → Facebook Login, valid OAuth redirect URI: `http://localhost:5173/login/oauth2/code/facebook` |
| `ADMIN_USERNAMES=alice,bob` | Promote these accounts to ADMIN (at startup / next sign-in) |
| `JWT_SECRET` | Signing key for access tokens, 32+ random characters. Optional locally (a random key per run is used); **required** on AWS |
| `VCHAT_EVENTS=kafka` | Kafka mode. First run `docker compose --profile kafka up -d kafka` |
| `LOG_LEVEL=DEBUG`, `SLOW_CALL_MS`, `SLOW_QUERY_MS` | Logging and slow-call thresholds |
| `-Dspring-boot.run.profiles=sql` (Maven flag) | Print every SQL statement with parameters and Hibernate statistics (N+1 hunting) |

Frontend (`frontend/.env.local`): `VITE_GOOGLE_CLIENT_ID`, `VITE_GOOGLE_API_KEY`, `VITE_ONEDRIVE_CLIENT_ID` for cloud sticker import.

### Voice engines and languages

| Engine | vi | en | zh | ja | ko | es | ru | Cost |
|---|---|---|---|---|---|---|---|---|
| Browser (Web Speech) | ✓* | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | free, *depends on OS voices |
| ElevenLabs `eleven_multilingual_v2` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | free tier ~10k chars/month |
| Google Cloud TTS | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | free tier for standard voices |
| COEIROINK | | | | ✓ | | | | free, runs locally |

## Architecture notes

- **Security** (Spring Security):
  - **Access token:** a JWT valid for 15 minutes, sent as `Authorization: Bearer` on API calls and in the STOMP `CONNECT`.
    It is kept only in page memory, never in `localStorage`.
  - **Refresh token:** valid for 30 days, in an `HttpOnly; SameSite=Strict; Path=/api/auth` cookie. The DB stores only its
    SHA-256 hash, and every use rotates it. Re-using an already-rotated token (a stolen copy) revokes every session from that
    login. Page load and expiry call `POST /api/auth/refresh`.
  - **Revocation:** **Log out** revokes the cookie and kills the current access token at once (Redis denylist).
    **Log out of all devices** and admin **Disable** revoke everything for that user and sign out open tabs over the WebSocket.
  - **Roles:** `USER` / `ADMIN`. `/api/admin/**` and `/actuator/**` (except health) require ADMIN, checked by the URL rules
    and by `@PreAuthorize`. Bootstrap the first admin with `ADMIN_USERNAMES`. Moderators get ⋮ → Admin (users) and a
    "Remove (moderator)" option on messages.
  - **Google/Facebook:** `oauth2Login`. The account is matched on the provider's user id, never on email, which prevents
    account takeover. The session is handed over through the refresh cookie, so no token ever appears in a URL.
  - **Hardening:** a strict Content-Security-Policy on the built app (no `unsafe-eval`; PixiJS is patched), HSTS and
    X-Frame-Options from CloudFront, Origin checks on the cookie endpoints, BCrypt passwords, and no default `JWT_SECRET`
    (production refuses to start without a strong one).
  - **Rate limits:** login 10 per username per 5 minutes, sign-up 10 per IP per hour, refresh 60 per IP per minute,
    sending 30 messages per 10 seconds.
- **Events.** Default `local` mode: in-memory STOMP broker plus `@Async` bot turns; one backend instance.
  `kafka` mode: `vchat.bot-requests` (durable bot jobs, retried, then dead-lettered to `.DLT`) and `vchat.chat-events`
  (every instance consumes all events and pushes to its own WebSocket sessions), so you can run several instances.
  Both topics are keyed by conversation id, which keeps each chat's events in order.
- **Performance.** See [backend/perf/README.md](backend/perf/README.md): indexes, N+1 fixes, caching layers, measured
  EXPLAIN plans, and the partitioning/sharding roadmap.

## Deploy to AWS

```bash
cd frontend && npm run build
```

```bash
cd infra && npm install && npx cdk bootstrap && npx cdk deploy
```

Then put the API keys into the `AppSecrets` secret (its ARN is printed as `SecretsArn`) and restart the service. Register
`https://<AppUrl>/login/oauth2/code/google` (and `/facebook`) with the providers.

```bash
aws secretsmanager put-secret-value --secret-id <SecretsArn> --secret-string '{"ANTHROPIC_API_KEY":"sk-ant-...","ELEVENLABS_API_KEY":"","GOOGLE_TTS_API_KEY":"","GOOGLE_CLIENT_ID":"","GOOGLE_CLIENT_SECRET":"","FACEBOOK_CLIENT_ID":"","FACEBOOK_CLIENT_SECRET":"","JWT_SECRET":"<keep the generated value>"}'
```

Logs go to the CloudWatch log group `/vchat/backend` as JSON with `requestId`/`userId` fields (query them with Logs
Insights). Postgres slow queries and `auto_explain` plans go to the RDS log group. The **VirtualFriends** dashboard shows
requests, 5xx, CPU/memory, Claude failures and recent errors. CI/CD is in `.github/workflows/deploy.yml`.

## Known limits / next steps

- AWS runs **one** backend task in `local` event mode. To scale out, set `VCHAT_EVENTS=kafka` with Amazon MSK (Serverless
  needs the `aws-msk-iam-auth` library), raise `desiredCount`, and keep ALB stickiness on.
- CloudFront talks to the ALB over HTTP. Add an ACM certificate and a custom domain, and restrict the ALB to CloudFront (prefix list + secret header).
- Kafka events are published after the DB commit. A crash in between can drop one realtime event (clients resync on reload).
  For guaranteed delivery use the transactional outbox pattern (an outbox table plus Debezium CDC).
- Live2D needs Live2D's proprietary Cubism Core (loaded from their CDN). Check their license if you publish commercially.
- Unity builds must be uncompressed and made with the VChatBridge template (see `unity/README.md`).
- Offline history is kept by one tab at a time (the OPFS pool can't be shared); other tabs work normally without it.
  Messages can't be sent offline (no outbox queue yet). Browsers may evict the copy under storage pressure.
