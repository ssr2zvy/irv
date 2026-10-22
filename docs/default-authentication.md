# Default authentication policy

Authentication is mandatory in every profile. Sandbox/terminal/preview features
remain disabled. See [mandatory authentication](mandatory-authentication.md).

Two independent controls apply:

1. `ApplicationBoundaryConfiguration` remains a terminal route allowlist. Unknown routes
   return 404 even for an authenticated user. Health is served only on port 8080;
   the diagnostic UI and narrowly listed auth routes use loopback port 8081.
2. `ZitadelSecurity` applies `BoundSessionAuthorization` to **any request** reaching
   its security chain except the exact bootstrap routes below. There is no list
   of protected routes to remember to extend.

| Exception | Method | Additional controls |
|---|---|---|
| `/auth/csrf` | GET | Trusted ingress and certificate admission |
| `/login/oauth2/code/zitadel` | GET | Same ingress, bound login transaction, state/nonce/PKCE and token validation |
| `/oauth2/authorization/zitadel` | POST | Same ingress, Origin and CSRF |
| `/auth/signup`, `/auth/signup/resume` | POST | Same ingress, Origin and CSRF; signup requires invite |

The boundary directly serves `/`, `/auth/diagnostics`, `/auth/app.js`, `/auth/style.css` and
`/auth/status` for GET/HEAD after trusted-ingress and certificate admission. These are explicit pre-login exceptions;
status exposes no token, certificate fingerprint or account identity. The
internal `/api/health` GET/HEAD exception is not exposed on port 8081.

For all other requests that reach the security chain, the manager requires both
an authenticated ZITADEL OIDC principal and a current server-side bound session,
matching subject and issuer, trusted ingress, exact certificate (while enabled),
and five-minute idle/eight-hour absolute expiry. Unsafe methods also require the
exact application Origin, and Spring's CSRF protection remains enabled. A bearer
JWT or a Spring authentication object without its valid bound session is
insufficient. The check does not extend idle expiry.

The same authentication chain and route boundary apply regardless of profiles.
Unfinished legacy components are explicitly deferred in every profile.

## When implementing features later

Keep the default manager as the catch-all when replacing the diagnostic route
boundary with application routing. Reuse it in the future runtime; do not restore
the old permit-all fallback. Registering a controller is not permission to expose
it in the current runtime. New public exceptions require explicit review;
do not add wildcard exceptions or a higher-priority unauthenticated chain.

Authentication does not infer resource ownership: sandbox operations must resolve
an authorized resource for the current immutable account ID before acting.
WebSocket upgrades must pass the same HTTP gate, and the implementation must
add expiry/revocation checks for open connections/messages. Preview proxies and
other listeners must enforce authentication before forwarding; Spring cannot
protect a listener or direct URL outside its filter chain. These features remain
unimplemented/disabled and are not claimed as protected by this patch alone.

Tests exercise an unnamed future controller through the real security chain,
then separately confirm the current boundary still blocks that controller.
They cover missing login/binding, wrong account, certificate switching/revocation,
forged ingress, both expiry limits, CSRF and Origin. Existing OAuth/invite tests
and packaged HTTP isolation checks remain the live-profile regression tests.

## Logging

[Authentication logging](authentication-logging.md) provides the shared outer
request logger and explicit authentication stage events. Preserve its ordering
before the feature boundary and security filters when introducing new routes.
