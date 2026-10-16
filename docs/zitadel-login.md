# ZITADEL login: current Flow 2 configuration

The earlier manual certificate/account admission map has been removed. Use [Flow 2 enrollment](flow2-enrollment.md) for the current signup flow, secrets, invite ledger, provider service-account role, limitations and rollout. IRV_AUTH_ADMISSIONS_JSON and IRV_AUTH_ALLOWED_CERT_SHA256 no longer control access.

## Existing provider application

| Setting | Value |
|---|---|
| Issuer | https://irv-instance-4ftbgu.us1.zitadel.cloud |
| Client ID | 395286007051839802 |
| Project ID | 395285999653022010 |
| User organization ID | 395285989452532411 (irv-users) |
| Required role | irv_user |
| Callback | https://iruvu.space/login/oauth2/code/zitadel |
| Client authentication | none; authorization code with S256 PKCE |

Keep irv-org and its administrator separate from normal application users. The enrollment service account must have ORG_USER_MANAGER in irv-users only. Runtime signup now requires its PAT; ordinary OAuth login still has no client secret. Do not supply the instance-wide setup token.

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


## Local validation

```sh
cd backend
mvn -Dtest=dev.irv.authonly.ZitadelFlowTest,dev.irv.authonly.EnrollmentTest test
mvn -DskipTests package
cd ..
python3 scripts/test-auth-only.py --jar backend/target/irv-0.1.0.jar
```

The full legacy test suite also requires the existing throwaway IRV_ENCRYPTION_KEY and temporary blob directory. See the patch VALIDATION.md for tests actually executed. Tests use a mock identity provider; they do not enroll a real iPhone passkey or mutate ZITADEL.
