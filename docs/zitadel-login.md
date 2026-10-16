# irv: ZITADEL login and certificate-bound diagnostic sessions

## Scope and status

This patch implements the Spring side of authorization-code login with PKCE, verified-ID-token checks, passkey/freshness/role admission, and an opaque server-side session bound to the exact approved certificate. It updates the diagnostic UI to report real completed checks.

It does not provision test users, grant roles, enroll passkeys, implement invite redemption, configure Cloudflare rules, or enable sandbox routes. Those are separate steps. Missing configuration denies login instead of granting a default account. Mock-provider tests are not live Safari/ZITADEL verification.

The sole deployment profile remains `auth-only`. Existing private Fly deployment, health port, tunnel, volume, deploy.sh and GitHub workflow stay in place. No new service, database, public IP, paid feature or provider billing call is added.

## Existing provider configuration

| Setting | Value |
|---|---|
| Issuer | `https://irv-instance-4ftbgu.us1.zitadel.cloud` |
| Client ID | `395286007051839802` |
| Project ID | `395285999653022010` |
| Application-user organization ID | `395285989452532411` (`irv-users`) |
| Administrator organization | Keep `irv-org` unchanged |
| Required role | `irv_user`, granted for the configured project and organization |
| Callback | `https://iruvu.space/login/oauth2/code/zitadel` |
| Post-login destination | `https://iruvu.space/` |
| Client authentication | `none`; authorization code with S256 PKCE, no client secret |

These nonsecret identifiers are in AuthSettings.java. The endpoints were checked against this instance's public discovery document. No provider-admin PAT is used at runtime. RS256 is the only accepted ID-token signing algorithm in this patch; it is advertised by the instance. If the application actually issues a different algorithm, investigate and explicitly review that change; never bypass signature verification.

## Fly runtime configuration

| Variable | Required behavior |
|---|---|
| `TUNNEL_TOKEN` | Existing connector token; keep it |
| `IRV_AUTH_INGRESS_SECRET` | 64 hex characters, shared with the Cloudflare header overwrite rule |
| `IRV_AUTH_ADMISSIONS_JSON` | JSON mapping immutable ZITADEL **user IDs** to approved SHA-256 certificate fingerprints |
| `IRV_AUTH_ALLOWED_CERT_SHA256` | Optional earlier standalone allowlist; only permits beginning login, never grants account admission |

Admission format (placeholders, not usable credentials):

```json
{"ZITADEL_USER_ID":["64_lowercase_hex_SHA256_fingerprint"]}
```

An actual ZITADEL user ID is numeric. It is **not** the client ID, project ID, organization ID, username, or email. A fingerprint must be 64 lowercase hex characters without colons. One certificate cannot be assigned to multiple accounts. Empty/missing admissions grant nobody access; malformed configuration prevents startup. Set this value using Fly's existing secrets interface. No database or Tigris variables are required for this profile.

The test user must already have a passkey and the `irv_user` project role. Manually admitting an account is an operator action for this test phase, not a substitute implementation of expiring single-use invitations. Do not use your instance administrator as the ordinary test user, enable public registration, or add a password fallback to make a test pass. Controlled user creation/enrollment is still the next provisioning task if no test user exists.

If you need a certificate fingerprint, it can be calculated from its **public PEM certificate** with `openssl x509 -in certificate.pem -noout -fingerprint -sha256`, then remove colons and lowercase it. The private key/P12 is not needed. An operator with a suitable environment can do this from the public PEM; reinstalling iSH is not a deployment requirement.

## Required Cloudflare forwarding

The original zone-level mTLS rejection rule remains required. For requests to `iruvu.space`, the request-header Transform Rule must **set/overwrite on every matching request**:

| Header | Source |
|---|---|
| `X-Irv-Ingress-Key` | Static value equal to the Fly ingress secret |
| `X-Irv-Cert-Verified` | `to_string(cf.tls_client_auth.cert_verified)` |
| `X-Irv-Cert-Revoked` | `to_string(cf.tls_client_auth.cert_revoked)` |
| `X-Irv-Cert-SHA256` | `cf.tls_client_auth.cert_fingerprint_sha256` |

Never copy these from incoming request headers, append rather than overwrite, or apply the rule only on successful validation while leaving client-supplied headers untouched on failure. Spring requires exactly one value per header, the loopback peer, port 8081, the ingress secret, and valid non-revoked certificate evidence. The connector routes to `http://127.0.0.1:8081`. Port 8080 serves only `/api/health`.

This is the earlier minimal trusted-header contract; it does not forward a full RFC 9440 certificate. Spring trusts Cloudflare's certificate verification through that protected ingress. It does not perform the browser TLS handshake. The ingress secret is not a defense against a compromised root process, connector, Cloudflare administrator or deployment authority.

## Login and requests

1. The diagnostic page obtains a CSRF token, then POSTs the login form. Both steps require an approved certificate; the POST also requires the exact application Origin.
2. Spring creates a five-minute, single-use login transaction containing the OAuth state/nonce/PKCE material and the exact certificate fingerprint. Starting another login replaces the older transaction.
3. The browser is redirected to hosted ZITADEL login with `prompt=login`, `max_age=0` and S256 PKCE.
4. The callback requires the same pre-login session and certificate. Spring exchanges the code and validates the ID token's RS256 signature, issuer, audience/authorized party, expiry and nonce. Wrong/replayed state or expired transaction fails.
5. The additional policy requires exactly the signed `amr` values `user` and `mfa`, rejects password/OTP/unknown extras, and requires `auth_time` no earlier than the login transaction (second precision). The authenticated subject must be admitted locally, mapped to that certificate, and carry the required organization role.
6. Spring rotates the session ID and creates the certificate-bound session. The browser receives a host-only `__Host-irv` cookie with Secure, HttpOnly, Path=/ and SameSite=Lax. Tokens are never returned to frontend JavaScript or local storage. The access token is not used to authorize irv requests and is not retained by the authorized-client repository.
7. Protected requests require the opaque session cookie and exactly the same approved certificate. A JWT Authorization header alone grants nothing. Different valid certificates cannot substitute for the bound certificate.

ZITADEL source evidence: at commit `78cd78ace5f047133b21ffec31a3c21f005616e9`, `internal/command/session_model.go` maps user-verified WebAuthn to Passwordless; `internal/api/oidc/amr.go` maps it to `user` plus `mfa`. This interpretation depends on the reviewed policy keeping external IdPs and other factors disabled. `mfa` by itself is not passkey evidence. Test actual Cloud token behavior; if it differs, the implementation denies access rather than guessing.

## Session lifetime and revocation

- Idle expiry: five minutes; absolute expiry: eight hours; whichever is first.
- Only CSRF- and Origin-protected `POST /auth/activity` extends idle time. The diagnostic UI sends it on deliberate foreground pointer/keyboard input, at most once per 15 seconds. Background status polling, visibility changes, automatic refresh and protected-probe GETs do not extend it. A malicious authenticated client could synthesize activity; browser `isTrusted` is a UI rule, not server proof of human intent. Absolute expiry still applies.
- Logout requires CSRF, Origin, current session and matching certificate. It invalidates the irv session and expires the cookie. It does not claim to terminate the provider's SSO session; every new irv login nevertheless demands fresh passkey evidence.
- Sessions are in-memory, appropriate for the current **single Machine**. Restart/deploy revokes all sessions and login transactions. Session persistence is disabled; no sessions or tokens are written to the volume by this implementation.
- To revoke a user/certificate from irv, remove its operator admission and restart/deploy so old sessions are discarded. Cloudflare revocation must also be propagated/enforced at the edge. Removing only a ZITADEL role does not immediately revoke an existing local session; it blocks the next qualifying login. There is no automated provider webhook/revocation synchronization in this patch.
- Do not scale to multiple Machines without a shared session store and coordinated admission/revocation. No distributed-session claim is made.

## Diagnostics and limits

Eight rows report: Spring, trusted certificate evidence, certificate approval, verified login transaction/token, fresh passkey, account/role admission, account-certificate match, and current bound session. Successful-login rows are derived from the valid bound session, not from browser flags or the mere presence of a cookie. Failure clears authenticated status. Only fixed public error codes appear; raw provider exceptions and tokens are not printed.

`GET /auth/protected` is an authenticated probe. Sandbox APIs, terminal WebSockets, logs and previews remain inaccessible in `auth-only`; this patch is not their authorization implementation. No invite UI or self-service recovery is enabled. Rejection of every provider enrollment/recovery route remains a live acceptance task, not something proven solely by the local session code.

## Validation and rollout

```sh
cd backend
mvn -Dtest=dev.irv.authonly.ZitadelFlowTest test
mvn -DskipTests package
cd ..
python3 scripts/test-auth-only.py --jar backend/target/irv-0.1.0.jar
```

The complete legacy test suite additionally requires a throwaway `IRV_ENCRYPTION_KEY` (base64 encoding of 32 random bytes), as before; do not use a production key in tests. The author ran all 16 Maven tests successfully with a temporary test key, plus the real HTTP smoke script.

Deploy with the existing deploy script/workflow. No workflow edit is required. Until forwarding, admissions and a passkey-enrolled role-granted user exist, the page will truthfully report missing configuration or deny login.

Before production admission, test on the actual iPhone: login and explicit protected probe; password/OTP/stale-SSO rejection; wrong/missing/revoked certificate; copied cookie with another certificate; idle and absolute expiry; CSRF and logout; no direct-origin bypass; restart revocation. Verify all Cloudflare rules cover the callback as well. No hardware key/Face ID requirement is added by this patch; the actual passkey prompt depends on the iPhone authenticator.

## Cost

No paid feature, billing operation, new instance, database or extra deployment is introduced. Existing ZITADEL plan limits, Cloudflare configuration, domain and Fly hosting costs still apply. The app uses normal OAuth and public signing-key endpoints; it does not call the provider's administrative APIs.
