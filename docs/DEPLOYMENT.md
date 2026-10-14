# Deployment

## Required services

- Fly organization and an API token authorized to create apps, Machines, and optional volumes. Keep it only in the control-plane secrets. Each sandbox gets a unique private network and its own short-scope agent bearer token.
- PostgreSQL for production metadata. `DATABASE_URL` is a **JDBC URL**, such as `jdbc:postgresql://host:5432/irv?sslmode=require`; set username and password separately. Do not put a `postgres://` URL into this field.
- Tigris bucket and its S3 access key/secret. The backend SDK uses `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION=auto`, `S3_ENDPOINT=https://fly.storage.tigris.dev`, and `S3_BUCKET`.
- Optional Twilio Verify service plus account SID/auth token for SMS. Without it, SMS is hidden and TOTP works normally with Microsoft Authenticator and Duo Mobile.
- A primary application hostname and a separate wildcard preview domain. Production previews should use a separate registrable domain so user-created pages are isolated from the control plane. Configure wildcard DNS/TLS to the same backend. `IRV_PREVIEW_ORIGIN=https://preview-domain.example`; the generated URL is `https://<48-character-token>.preview-domain.example`.

Preview URLs are temporary bearer capabilities, expire after one hour, and are minted only by the owner after full MFA authentication. Anyone possessing a URL can use that preview until expiry. Keep them private. Main session cookies are host-only and are not sent to the preview host. Control-plane mutation APIs also require the explicit request header and matching origin. WebSocket terminal sessions expire/revoke with the login session.

## Build and publish sandbox images

Example commands; choose your own registry and tags. Use Linux amd64 for the provided JAVA_HOME path.

```sh
docker build --platform linux/amd64 -f images/Dockerfile.blank -t ghcr.io/YOUR_ACCOUNT/irv-blank:v1 .
docker build --platform linux/amd64 -f images/Dockerfile.java \
  --build-arg IRV_BASE=ghcr.io/YOUR_ACCOUNT/irv-blank:v1 \
  -t ghcr.io/YOUR_ACCOUNT/irv-java:v1 .
docker push ghcr.io/YOUR_ACCOUNT/irv-blank:v1
docker push ghcr.io/YOUR_ACCOUNT/irv-java:v1
```

Set `IRV_BLANK_IMAGE` and `IRV_JAVA_IMAGE` to immutable `registry/repo@sha256:...` references. The manual image-catalog endpoint requires digests. Use images Fly can pull; public GHCR packages are simplest. Private registry authentication is not configured by this ZIP's Fly adapter.

## Deploy the control plane

Deployment is an operator action; no GitHub deployment workflow is included. The environment-maintenance worker is deferred, so image and Fly integration updates also require manual work until it is implemented.

1. Generate `.env` using `scripts/init_env.py`, then fill in service credentials.
2. Create the Fly control-plane app and PostgreSQL database using your preferred provisioning workflow.
3. Edit `fly.toml` with the control-plane app name and region.
4. Set `IRV_PUBLIC_ORIGIN` to its HTTPS URL and `IRV_SECURE_COOKIES=true`.
5. Import the `.env` values as Fly secrets (`fly secrets import < .env`). Do not commit `.env`.
6. Run `fly deploy --ha=false`. Keep exactly one backend Machine; the Fly configuration disables automatic shutdown of the control plane.
7. Register the admin with the bootstrap invite, enroll MFA, save recovery codes, and remove `IRV_BOOTSTRAP_INVITE` from secrets.
8. Enter actual prices in Administration, create a sandbox, and run the credentialed tests before admitting other users.

The backend serves the built React app, API, terminal WebSockets and preview proxy through one port. Agents connect outward to the public TLS backend, so they do not require inbound IPs or a shared private network with the control plane.

Choose working storage:

- `IRV_WORKING_STORAGE=ephemeral`: no Fly volume; workspace resides on the Machine disk while it exists, and Tigris is the durable source after deletion.
- `IRV_WORKING_STORAGE=volume`: create a 10 GiB volume (`IRV_VOLUME_GB` configurable). The optimizer can retain it after destroying the Machine or delete it after a verified snapshot.

Both retain only the designated folder as the durable contract. Users run without root/sudo or privileged capabilities. Install system-wide dependencies by changing and rebuilding the predefined images; user-local tools can be set up in entrypoint/helper scripts. This is not a privileged Docker-in-Docker service.

Limits: 5 active sandbox records per user, 20 GiB stored chunks per sandbox, 100,000 entries per snapshot, 10 configured preview ports/processes, 4 MiB HTTP preview request/response payloads. Preview WebSockets are supported, including Vite HMR; arbitrary additional WebSocket subprotocol negotiation needs an explicit allowlist extension. HTTP previews are buffered and do not support SSE/streaming responses. These are intentional resource bounds in this release.

## Recovery

Protect the encryption key and back up PostgreSQL as well as Tigris. Losing PostgreSQL loses account/snapshot metadata even if chunks remain; losing the encryption key makes enrolled MFA secrets unreadable. Recovery codes replace the second factor after a correct password; they do not reset a forgotten password. Administrator-assisted password/MFA recovery currently requires an authenticated operator database procedure; there is no email reset endpoint or self-service reset UI.

`GET /api/sandboxes/{id}/snapshots` lists backups. `POST /api/sandboxes/{id}/restore/{snapshot}` selects one while the sandbox is fully cold and has no Machine/volume; current app-side TOML/scripts overlay the selected backup on start. The main UI shows latest snapshot status; historical selection is currently via the API.
