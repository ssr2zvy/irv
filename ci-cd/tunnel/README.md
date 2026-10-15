# Separate Cloudflare Tunnel connector

This step establishes connectivity only. It does not implement client-certificate enforcement, Auth0, or certificate-bound sessions. Route only to the existing status listener until those protections are implemented and verified.

## Architecture

Browser HTTPS -> Cloudflare hostname -> existing named tunnel -> cloudflared in `iruvu-tunnel` -> Fly private IPv6 network -> `http://iruvu.internal:8081`.

| Fly app | Process | Secrets and storage |
| --- | --- | --- |
| `iruvu` | Existing Java application | Existing app secrets and persistent volume; no tunnel token |
| `iruvu-tunnel` | cloudflared only, non-root UID 65532 | Only `TUNNEL_TOKEN`; no volume or app database |

The connector initiates outbound connections to Cloudflare. Neither Fly app publishes a public service or needs a public IPv4/IPv6 allocation. Fly assigns private IPv6 automatically. Both apps must be in the same Fly organization and private network. This assumes the existing default organization network; if custom networks are used, attach both to the same intended network.

The status listener explicitly binds IPv6. Port 8081 only serves the connectivity page; application API paths on that listener remain blocked. Do not point the tunnel at port 8080 yet. `localhost` on the connector Machine would refer to the connector, not Java.

## 1. Apply and deploy the Java app change

Apply this patch, commit/merge, and deploy `iruvu` using the existing workflow with action **deploy**, or the unchanged `bash ci-cd/deploy.sh deploy`. This removes the prior public HTTP service and leaves internal health checks. The Java Dockerfiles and entrypoint are unchanged.

Check `flyctl status -a iruvu` and `flyctl checks list -a iruvu`. The `boot` and `status` checks should pass. The old `iruvu.fly.dev` page is intentionally unavailable. Existing allocated public addresses are unnecessary; removing the service blocks its public route. If addresses were allocated, inspect `flyctl ips list -a iruvu` and release each unneeded public address with `flyctl ips release ADDRESS -a iruvu`. Do not remove private network addresses.

## 2. Create the connector app once

Use an operator Fly login allowed to create apps. The existing iruvu-scoped deployment token cannot create/deploy a different app. Inspect the organization with `flyctl status -a iruvu` and `flyctl orgs list`, then run:

```bash
flyctl apps create iruvu-tunnel --org YOUR_ORG_SLUG
```

Replace `YOUR_ORG_SLUG` with the same organization as iruvu. If the globally unique app name is unavailable, choose another and update this directory's `fly.toml` plus the commands below. The Cloudflare tunnel name does not have to change. No Machine or volume needs manual creation.

## 3. Move the secret to the connector app

In the Fly dashboard, add `TUNNEL_TOKEN` to **iruvu-tunnel**, using only the token value from the existing Cloudflare tunnel's installation screen. Fly cannot reveal the value previously stored on iruvu; recopy it from Cloudflare. Never put it in a source file, build argument, command screenshot, or chat.

Then remove the misplaced token from the Java app:

```bash
flyctl secrets unset TUNNEL_TOKEN -a iruvu
```

That command updates/restarts the Java app. Check its health again. The token is not known to have leaked; if it was exposed, rotate it in Cloudflare and use the replacement only on the connector app. The connector needs no Fly API token, application encryption key, database credentials, or Tigris credentials.

## 4. Deploy the connector

From the repository root, using a Fly login or deploy token authorized for **iruvu-tunnel**:

```bash
cd ci-cd/tunnel
flyctl deploy --config fly.toml --remote-only --ha=false --wait-timeout 5m
```

This creates one shared-CPU, 256 MB Machine in iad. It has no volume and adds a separate compute charge. 256 MB is an initial allocation for this small connectivity test; monitor memory and resize if necessary. Do not use `fly launch`, allocate public IPs, or mount the Java volume here.

```bash
flyctl status -a iruvu-tunnel
flyctl checks list -a iruvu-tunnel
flyctl logs -a iruvu-tunnel
```

Wait for Cloudflare to report the existing tunnel **Healthy** and the connector `/ready` check to pass. This proves a connector-to-Cloudflare connection, not Java health or working hostname routing. The private metrics endpoint on port 2000 is not published through the tunnel.

## 5. Add the hostname route in Cloudflare

Open the existing tunnel's **Routes -> Add route -> Published application**. Select your active domain and intended hostname. Set service type **HTTP** and service URL **iruvu.internal:8081** (combined URL: `http://iruvu.internal:8081`). Save. Do not add an A/AAAA record pointing at Fly; Cloudflare manages the tunnel DNS route. Do not enable a catch-all route to Java's app port.

Open `https://YOUR_HOSTNAME/`: expect `connected successfully`. Request `https://YOUR_HOSTNAME/api/health`: expect 404 from the isolated status listener. The real backend health check remains internal at port 8080. If Java is stopped, `.internal` discovery will not wake it; keep both Machines running for this stage.

## Operations and boundaries

- Java deployments and the existing deploy/stop workflow affect **iruvu only**. They neither deploy nor stop the connector.
- Repeat step 4 when deliberately updating the connector. The official image is version-pinned to 2026.10.0, not digest-pinned; runtime auto-update is disabled. This does not implement the deferred automatic updater.
- cloudflared is the single foreground process; Fly supervises process exits using the configured `always` restart policy. It reconnects internally after network interruptions. Health checks report readiness; they are not a custom health-failure restart worker.
- To stop the connector, find its Machine ID with `flyctl machine list -a iruvu-tunnel`, then `flyctl machine stop MACHINE_ID -a iruvu-tunnel`. To resume, use `flyctl machine start MACHINE_ID -a iruvu-tunnel`. Manual stopping disconnects the tunnel. There is no incoming-traffic autostart.
- Separate Machines prevent ordinary Java process access to the connector's environment/token. This does not prevent attacks through reachable services or compromised Fly administration. Apps sharing the default private network can reach one another; this is not a connector-only firewall or authenticated origin channel.
- Before exposing the actual application, implement mTLS enforcement, trusted certificate forwarding with a verified origin trust boundary, Auth0 admission/login, and exact-certificate session binding. Do not trust certificate headers just because a request arrived over `.internal`.
- Neither public Fly IPs nor a second domain is required. Existing domain registration and both apps' hosting remain billable. This patch does not purchase or provision resources by itself.

## References

- https://developers.cloudflare.com/tunnel/guides/kubernetes/ (separate connector deployment, token environment, /ready)
- https://developers.cloudflare.com/tunnel/reference/run-parameters/
- https://github.com/cloudflare/cloudflared/releases/tag/2026.10.0
- https://fly.io/docs/networking/private-networking/
- https://fly.io/docs/reference/configuration/
