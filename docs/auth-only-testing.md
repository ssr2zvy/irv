# irv auth-only testing

## Scope and current result

This is an **authentication preparation patch**, not completed Auth0 integration.
It replaces the temporary `connected successfully` page with one live diagnostic page.
It does not create an Auth0 tenant, log a user in, accept credentials, create an authenticated
session, or implement the password AND passkey policy. Those rows explicitly say **Not implemented**.
There is no simulated success path or temporary authentication bypass.

The latest intended policy is password AND passkey, plus a client certificate, approved-user
admission and an exact-certificate-bound session. This supersedes the older passkey-only wording.
Auth0's ability to enforce the AND requirement across enrollment, login and returning sessions
is still unverified. Do not substitute the password OR passkey toggle.

## Deployment and isolation

`fly.toml` selects `SPRING_PROFILES_ACTIVE=auth-only`. This must be the sole active profile.
Visitors cannot change profiles by query string, cookie, header or login. A profile change is an
operator startup/deployment operation. Existing workflow, deploy script, connector supervision,
Machine, region, volume and tunnel token remain in use. No new Fly app or public IP is required.
Existing Fly secrets can override file configuration: remove any stale `SPRING_PROFILES_ACTIVE`
secret override before deployment if present. Do not change `TUNNEL_TOKEN`.

Keep the tunnel route for `iruvu.space` as `http://127.0.0.1:8081` and keep the existing WAF
certificate rule enabled. No new secret is required just to display the diagnostic page.

| Listener | Allowed routes | Behavior |
|---|---|---|
| 8080, internal | GET/HEAD `/api/health` only | Unauthenticated operational readiness; 200/503 based on Java and connector readiness |
| 127.0.0.1:8081, tunnel | GET/HEAD `/`, `/auth/app.js`, `/auth/style.css`, `/auth/status` | Diagnostic shell and current status |
| Either | Everything else | 404; unsupported methods on allowed routes receive 405 |

`/api/health` remains unavailable on 8081. Fly checks it on 8080. A green health check does
not mean any authentication checks passed. No public Fly service should be added.

Legacy authentication, sandbox controllers, previews, terminal sockets, agent endpoints,
maintenance workers, lifecycle workers, database and Tigris/provisioning services do not load.
Static resource auto-serving is disabled. Old source and dependencies remain in the repository
and container build; this patch isolates **runtime components**, not the build dependency graph.
The existing volume remains attached but auth-only Java creates no database or legacy accounts.
The existing container entrypoint may still create empty data directories.

The diagnostic shell intentionally loads before full user authentication, behind Cloudflare's
mTLS gate. It discloses only generic stage results, not account details, fingerprints or secrets.
An unapproved certificate can see a failure report, but cannot access any application function.
The report creates no session and never accepts a supplied cookie/JWT as evidence.

## Live checks

1. Spring connectivity.
2. Cloudflare certificate verification, from protected forwarding.
3. Spring certificate authorization against a deployment-managed fingerprint allowlist.
4. Auth0 token and login-transaction validation — not implemented.
5. Auth0 password authentication — not implemented.
6. Auth0 passkey authentication — not implemented.
7. Invite-created user admission — not implemented.
8. User-to-certificate approval — not implemented.
9. Current valid certificate-bound session — not implemented.

All statuses originate in Spring. The UI polls every 15 seconds while visible, refreshes when
returning to the tab, and supports manual refresh. Network/JSON failures clear earlier results.
`authenticated` is always false in this preparation build. A later implementation must derive
success from actual validation and current session state, never from environment toggles.

## Optional: turn on the implemented certificate checks

Without these settings the page works, but certificate evidence/approval show **Not configured**.
The working WAF gate alone does not prove certificate identity to the application.

Two operator values enable the checks:

| Fly runtime setting | Meaning |
|---|---|
| `IRV_AUTH_INGRESS_SECRET` | A fresh random 32-byte value encoded as 64 hexadecimal characters; shared only with Cloudflare's request-header rule |
| `IRV_AUTH_ALLOWED_CERT_SHA256` | Comma-separated approved certificate SHA-256 fingerprints, 64 hex characters each, no colons |

The ingress secret is **not** a certificate private key, Auth0 credential, or tunnel token.
It prevents a local caller without that secret from fabricating accepted forwarding headers.
It is not a per-request signature and does not protect against a compromised Java process,
connector/root, or Cloudflare control plane. Keep it out of response headers and logs.
Only the first value is confidential; fingerprints are public identifiers.

Generate the ingress value using a trusted cryptographic random generator (for example,
`openssl rand -hex 32` in your own environment), and enter it in Fly runtime secrets.
Do not put the value in Git, documentation, screenshots, or chat. Use a different value per deployment.

Obtain the SHA-256 fingerprint from the certificate details in Cloudflare or from the **public**
certificate: `openssl x509 -in iruvu-iphone.pem -noout -fingerprint -sha256`.
Remove the label and colons when setting the allowlist. Do not upload the private key.
The allowlist is an initial operator-managed record; it is not an irv account/device database.
Changes require restarting/redeploying Java. Real user-certificate records and runtime revocation
are still required before this becomes the full security model.

In Cloudflare, create a **Request Header Transform Rule** matching:

```text
http.host eq "iruvu.space"
```

Use **Set/overwrite**, not append, for every header on **every matching request**:

| Header | Operation | Value |
|---|---|---|
| `X-Irv-Ingress-Key` | Set static | The exact ingress secret stored in Fly |
| `X-Irv-Cert-Verified` | Set dynamic | `to_string(cf.tls_client_auth.cert_verified)` |
| `X-Irv-Cert-Revoked` | Set dynamic | `to_string(cf.tls_client_auth.cert_revoked)` |
| `X-Irv-Cert-SHA256` | Set dynamic | `cf.tls_client_auth.cert_fingerprint_sha256` |

Do not derive any of these values from incoming request headers. Overwriting removes client
spoofs. Do not use a conditional rule that leaves supplied headers intact on failure. Retain
the separate WAF block rule for invalid/missing/revoked certificates across the entire hostname.
Do not allow other routes, Workers, proxy rules or later transforms to bypass or alter this trust
boundary. The backend accepts the proof only on its actual 8081 listener from loopback; it does
not trust `X-Forwarded-*` for that decision. Duplicate/malformed evidence is rejected.

This uses a minimal private header contract rather than forwarding the complete RFC 9440
certificate chain. It implements the fingerprint/verification needs of these diagnostics only.
The secret and valid evidence are checked anew on each report request; no pass result is cached.
Cloudflare configuration is manual and was not applied or live-tested by this patch.

## Required external tests and future integration

- Confirm Safari with the enrolled identity loads the report; another device without it is blocked.
- After forwarding setup, the certificate check and matching allowlist approval must pass.
- A valid certificate absent from the allowlist must fail Spring approval.
- Wrong/missing ingress proof and spoofed/duplicate headers must not pass.
- Verify invalid, expired and revoked certificates are blocked at the edge; a still-open
  WebSocket needs explicit revocation logic when WebSockets are reintroduced.
- Verify no public origin bypass. Same-organization Fly networking is not user authentication.
- Test Secure/HttpOnly/SameSite cookies, CSRF, state/nonce/PKCE, token signature/issuer/audience/
  expiry, session rotation/expiry/logout and current certificate matching in the later login patch.
- Implement single-use expiring invites, no recovery bypass, certificate lifecycle and exact
  account-certificate mapping. Certificate authorization must not automatically create accounts.
- Test permissions separately for APIs, sandbox ownership, terminals, logs and previews before
  those features are re-enabled. The current profile disables them entirely.
- Repository/deployment permissions, cloud administrator security, device compromise and
  source/secret access remain separate controls; this page cannot verify them or prove AAL3.

## Validation commands

Build normally, then run the bundled real-HTTP smoke check on a machine with Java 17 and Python 3:

```sh
cd backend
mvn -DskipTests package
cd ..
python3 scripts/test-auth-only.py --jar backend/target/irv-0.1.0.jar
```

Ports 8080, 8081 and 2000 must be free. The script runs two isolated app processes and a fake
local readiness server, tests allowed/blocked routes, secret/evidence checks, no fake-session
success, absence of legacy data creation and 200/503 health transitions. Test credentials are
local fixtures only; never copy them to a deployed environment.

`AuthOnlyIsolationChecks.java` additionally checks that legacy services, old auth and the
DataSource bean are absent from the Spring context. It is a standalone executable check,
not automatically picked up as a JUnit test by Maven.

## References

- https://developers.cloudflare.com/rules/transform/request-header-modification/
- https://developers.cloudflare.com/rules/transform/request-header-modification/reference/fields-functions/
- https://developers.cloudflare.com/ssl/client-certificates/enable-mtls/
- https://developers.cloudflare.com/ssl/client-certificates/forward-a-client-certificate/
