# Authentication event logging

The backend now writes one JSON object per audit event to stdout under logger
`irv.auth.audit`. Existing Supervisor forwarding sends these lines to Fly logs.
No log collector, paid service, new secret, database table or public log endpoint
is introduced. The active profile remains auth-only; unfinished features remain
disabled. This is operational logging, not a tamper-proof or durable audit archive.

## Reading events

Filter for `"schema":"irv.auth.v1"` in the Fly log message. Normal Spring startup
and cloudflared/Supervisor output remain separate non-audit lines. Fields:

| Field | Meaning |
|---|---|
| timestamp | UTC event time |
| event | Stage, e.g. ingress, certificate, transaction, id_token, signup, session |
| outcome | started, passed, failed, not_required, completed |
| reason | Fixed enum, e.g. invalid_secret, idle_expired, invite_already_reserved |
| request_id | Fresh backend-generated UUID for this HTTP request; returned in X-Irv-Request-Id |
| flow_id | Independent UUID for a login/signup attempt; retained in the pre-login session across redirects |
| session_ref | Independent UUID for an authenticated session; never the real session ID |
| method / route | Known HTTP method and route/template; unknown paths become <unmapped> |
| status / duration_ms | HTTP completion only; status and elapsed milliseconds |

Request IDs supplied by clients are ignored. Flow/session references grant no
access. A new login has a new flow reference; signup and the later explicit login
are separate flows. A callback without its original cookie cannot be linked to a
trusted prior flow and is logged with its new request ID. No attempt is made to
correlate it by printing OAuth state/code. References disappear with session loss
or restart; they are not permanent account identifiers.

A request completion with HTTP 303 does not mean authentication succeeded: failed
login also redirects. Read the login/OIDC stage events. `id_token:passed` means
Spring's decoder/signature/claim validation completed; `oidc:passed` is emitted only
after Spring's complete OIDC authentication (including nonce validation) succeeds.
Failures in those library stages are deliberately grouped. We do not guess whether
an opaque failure was a bad signature, network/key-fetch problem, nonce or audience.
PKCE parameters are never logged; token exchange success means the provider
accepted the exchange, not an independently instrumented provider-side PKCE check.

## Coverage

- The outer filter logs request start and completion before the auth-only boundary,
  security filters and controllers. It applies in every profile and to future
  routes without controller edits. Unknown paths, wrong ports/methods, CSRF,
  Origin, firewall and unauthenticated rejections are therefore visible.
- Ingress checks log missing config, untrusted connection or invalid forwarding
  secret. Certificate checks log accepted evidence, not-verified status,
  revoked/unknown status or malformed fingerprint. They log no fingerprint/key.
  With certificate policy disabled, the result is not_required, not passed.
- Login logs transaction/state/certificate/age validation, token exchange,
  ID-token/OIDC validation, passkey method evidence, freshness, role, subject,
  session creation and failure. These reflect the existing checks and do not add
  authentication methods or change their acceptance policy.
- Signup logs configuration/rate/input failures, invite eligibility/reservation,
  provider user creation, setup-link generation, role grant and completion or
  review-required failure. Invite codes, hashes and setup links are never emitted.
  An issued setup link does not prove the passkey was enrolled; later login does.
- Sessions log validation, binding mismatch, expiry detected on a request,
  invalidation, activity and logout. Browser inactivity by itself does not trigger
  an exact-time expiry event; there is no new background session scanner. Restart
  discards sessions without a per-session logout event.
- Repeated identical gate/helper results within one request are deduplicated;
  requests and distinct outcomes are not sampled. Public diagnostic assets log
  request outcomes, not fabricated authentication successes. Health and status
  polling produce logs too, so expect volume proportional to polling frequency.
- Async HTTP completion is captured with a listener and thread context is cleaned
  after dispatch. New asynchronous business-stage code must propagate audit
  context explicitly if it needs stage events; arbitrary worker-thread events
  are not automatically correlated. This does not implement WebSocket message
  logging or ongoing connection authorization for future features.

The request logger is registered first and the auth-only boundary second. Keep
that ordering for new filters. Requests rejected by the HTTP server before servlet
filter dispatch are outside its coverage. Exceptions escaping dispatch get a safe
unexpected_error completion (500 if no error status was set); no exception text
is copied into the audit event. Existing framework logs are a separate channel.

## Data handling

The event API accepts only stage/outcome/reason enums. It emits no bodies, query
strings, raw paths for unmatched requests, user names/emails, account IDs, client
IPs, cookies, session IDs, JWTs, OAuth codes/state/nonce/PKCE, ingress secrets,
certificate fingerprints, invite values or passkey registration links. Framework
handler templates can appear on completed future-controller requests; never put
credentials in route definitions. Do not enable Spring Security TRACE/DEBUG to
obtain more detail; its existing ERROR setting is retained.

Audit logging failures must not change authentication decisions. Accordingly,
logging is best effort: a broken/disabled stdout sink or abrupt process exit may
lose records. Fly retention and access permissions are external operational
settings; these events add neither storage retention nor operator access rights.

## Boundaries

Cloudflare-rejected requests never arrive here and are not logged by Java.
Hosted ZITADEL internal actions are not observed directly; only our API calls and
validated callback evidence are recorded. Browser frontend console errors are
not collected. Manual provider administration is not an application event stream.

## Validation

Tests capture emitted JSON from real mock-provider login/signup flows, stage
failures, expiry/logout and early rejection. They assert request/flow references,
secret exclusion, and async/error cleanup. The packaged HTTP smoke script checks
actual stdout format, paired request events and unchanged isolation/cookie flags.
No live Cloudflare or ZITADEL configuration is changed by this patch.
