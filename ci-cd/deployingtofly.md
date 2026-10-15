# One Fly app: iruvu

This replaces the separate connector deployment. One iruvu Machine runs Java and cloudflared, supervised together. The existing workflow deploys/stops both. No iruvu-tunnel app or second deployment credential is required.

## Apply and deploy

1. Apply this patch and merge it into main.
2. Ensure **TUNNEL_TOKEN** is set in **Fly -> iruvu -> Secrets**, containing only the token from Cloudflare's tunnel installer. Restore it there if earlier instructions moved it. Never put it in source, GitHub Actions, build arguments, or chat.
3. If a separate iruvu-tunnel Machine was created, stop it first: `flyctl machine list -a iruvu-tunnel`, then `flyctl machine stop MACHINE_ID -a iruvu-tunnel` for each Machine. Skip if that app was never created. Do not leave an old connector running with the previous route. Once this setup works, remove the unused app and its secret in Fly. The patch does not delete live resources automatically.
4. In the existing Cloudflare tunnel, configure the published application route: domain **iruvu.space**, blank subdomain, type **HTTP**, URL **127.0.0.1:8081**. Full origin URL: `http://127.0.0.1:8081`. Replace the old `iruvu.internal:8081` destination. Here localhost is the one Machine containing both processes.
5. Run the existing **Fly deployment** workflow on **main**, action **deploy**, or `bash ci-cd/deploy.sh deploy` using the existing iruvu-scoped FLY_API_TOKEN.
6. The script deploys, then runs a bounded readiness check inside the Machine through Fly SSH. It returns success only when Java's /api/health returns 200, which now requires the local connector's /ready to return 200. The deployment identity must permit Fly SSH on iruvu. If that check is denied, the command fails instead of falsely reporting readiness.
7. Verify Cloudflare reports **Healthy**, then open **https://iruvu.space/** for `connected successfully`. The same hostname's /api/health must return 404 because its route exposes only the temporary status listener.

No public Fly IPs are needed. Neither http_service nor public services are configured. A successful deployment/readiness check does not prove that your Cloudflare hostname route is configured; the browser check is separate.

## Architecture and secret handling

Browser -> Cloudflare -> outbound tunnel -> cloudflared -> loopback Java status listener on 8081.

| Process | OS user | Access |
| --- | --- | --- |
| Supervisor | root | Trusted initialization and process management; no HTTP/RPC control socket |
| Java | irv, UID 10001 | Existing app environment and /data; no TUNNEL_TOKEN environment variable |
| cloudflared | cloudflared, UID 10002 | Restricted runtime token file; clean environment without Java/Fly/Tigris secrets |

Initialization creates /run/irv-tunnel/token as root:cloudflared, mode 0640, in a 0750 directory. It is on ephemeral root storage, not the data volume. Java is not in the cloudflared group. Initialization execs Supervisor with TUNNEL_TOKEN removed, so Java does not inherit it. The connector receives only the token-file path in its arguments; its environment is cleared before execution. The original entrypoint prepares /data then switches Java to irv.

The connector readiness port 2000 and status listener 8081 bind loopback. Java's main port 8080 remains available privately for Fly checks and `flyctl proxy 8080:8080 -a iruvu`. Same-organization networking is not an authenticated origin boundary. Do not expose the actual app or trust certificate headers until the intended ingress/authentication protections exist.

## Lifecycle

- Machine start/deploy launches both processes. Missing TUNNEL_TOKEN fails initialization before Java starts in tunnel mode.
- The existing stop action stops the one Machine, hence both processes, preserving the data volume.
- Supervisor restarts crashed children. Repeated startup failures trigger the fatal-event listener to shut down the supervised group; Fly's always restart policy attempts to restart the Machine.
- cloudflared reconnects after network interruptions. Java health returns 503 when connector readiness fails. Health failure alone is not a custom restart worker.
- TERM gracefully stops Java first, then the connector, within Fly's 60-second shutdown allowance.
- Failure detection/recovery takes time. This provides coordinated lifecycle/readiness, not guaranteed simultaneous uptime or high availability.

## Deployment, storage, cost

The workflow is unchanged. deploy.sh adds a post-deploy readiness gate; its deploy/stop/cancel interface is unchanged. Only the existing GitHub Actions FLY_API_TOKEN is needed. Runtime TUNNEL_TOKEN stays on iruvu; no FLY_TUNNEL_API_TOKEN is needed.

Both application Dockerfiles are identical, including version-tag-pinned cloudflared 2026.10.0 and Supervisor. Runtime cloudflared self-update is disabled. This is not the deferred automatic dependency updater; tags are not digest pins.

The existing shared-CPU/1 GB Machine and 1 GB volume remain. No second Machine charge; the connector uses some of the existing Machine's CPU/RAM. Monitor and resize if necessary. Existing Fly hosting/storage/traffic and domain costs remain. H2 and local blob storage continue without DATABASE_URL or Tigris credentials.

For local Docker use, IRV_TUNNEL_ENABLED defaults to false and preserves Java-only startup. Fly sets it true. Tunnel mode uses the fixed supervised Java command and rejects arbitrary Docker CMD overrides.

## Security scope

Separate users, file permissions, and environment separation reduce cross-process exposure. They are not separate-kernel isolation: root/kernel or Fly administration compromise can affect both. Supervisor and initialization are trusted root components.

This patch does not implement Cloudflare mTLS enforcement, trusted certificate forwarding, Auth0 passkeys/invites, or certificate-bound sessions. The temporary page is intentionally accessible without login after routing works. The real UI/API is excluded from that route.

## Inspect

```bash
flyctl status -a iruvu
flyctl checks list -a iruvu
flyctl logs -a iruvu
```

Missing token prevents startup; invalid token prevents readiness. A healthy tunnel with no page indicates a route/origin issue; check the hostname route and obsolete connectors. Do not print secrets during diagnostics. See the ZIP's VALIDATION.md for executed checks and limits.

References: https://developers.cloudflare.com/tunnel/reference/run-parameters/ ; https://supervisord.org/configuration.html ; https://fly.io/docs/reference/configuration/
