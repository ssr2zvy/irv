# Mandatory authentication

Authentication no longer depends on a Spring profile. The default profile, arbitrary
named profiles, and installations still setting `auth-only` load the same ZITADEL
login, enrollment, certificate-bound sessions and terminal route boundary. No
profile can activate the deferred sandbox components. The internal Java package
`dev.irv.authonly` is retained to avoid unnecessary package/API churn; it does not
select a runtime mode.

## Admission and routes

- Port 8080 exposes only GET/HEAD `/api/health`, for internal readiness.
- Loopback port 8081 serves GET/HEAD `/`, `/auth/diagnostics`, `/auth/status`,
  `/auth/app.js`, `/auth/style.css` only after the configured trusted-ingress and
  certificate admission checks. Missing/invalid admission returns 403 with no page.
- `/` provides passkey sign-in and the existing invite-gated signup flow. The single
  bottom-right lock link is always present on this page, including after login.
- `/auth/diagnostics` preserves all eight checks, refresh, protected-request probe,
  expiry details and security limitations. It has a text link back to `/`.
- After login, `/` shows a signed-in confirmation and Sign out. No unfinished
  application screen or terminal is exposed.
- The existing exact auth-route/method allowlist, same-origin POST checks, Spring
  CSRF validation, OAuth state/nonce/PKCE and bound-session authorization remain.
- All other routes remain 404, including after successful login. Unknown routes,
  static SPA assets, old login, sandbox APIs and WebSockets do not dispatch.
- A future endpoint requires both explicit boundary integration and authenticated
  session/ownership enforcement. Spring's catch-all uses bound-session authorization.

Signup is available when the enrollment provider token and at least one unexpired
configured invite exist. If `IRV_AUTH_ENROLLMENT_ENABLED` is omitted, this configuration
enables signup automatically. An explicit `false` still disables it. An explicit
`true` with a missing/invalid provider token still rejects startup. Consumed invites
remain single use, independently enforced by the durable ledger.

`GET /auth/status` logs `signup_availability` with one fixed reason: `valid`,
`enrollment_disabled`, `provider_token_missing`, `invites_not_configured`, or
`invites_expired`. The sign-in page hides unavailable account creation without
configuration prose. No token, invite, email or identity is added to logs.

The sign-in page uses menu rows, compact headers and icon navigation adapted from
Telugu-now Settings. Create account opens a separate form view; the bottom-right
lock opens diagnostics. Operation failures use a temporary accessible notice.
Diagnostics retain all eight checks and place limitations in an expandable section.

`POST /auth/login` requires certificate admission, Origin and CSRF. It creates the
same certificate-bound, expiring, single-use OAuth transaction and returns JSON
containing the fixed provider authorization URL. The browser validates the exact
provider origin/path then navigates using `location.assign`. It never submits an
external form or follows a redirect through fetch. The CSP retains `form-action
'self'`. The former `/oauth2/authorization/zitadel` bootstrap route is no longer
exposed. Callback validation, PKCE, state, nonce, freshness and account role checks
are preserved. Do not log the authorization URL: it contains state/nonce values.

See [enrollment](flow2-enrollment.md) and [login](zitadel-login.md).
The certificate-required setting remains the existing operator policy.

## Feature isolation

`@DeferredFeature` excludes unfinished legacy services in all profiles. Its condition
is always false and has no runtime enable switch. Integrating a feature later is an
explicit source change, not a profile change. Legacy database autoconfiguration and
Flyway are disabled in shared application properties. Enrollment continues to use
its dedicated invite ledger when enabled.

## Deployment and verification

Rebuild/deploy from source using the existing Docker workflow. `fly.toml` no longer
sets `SPRING_PROFILES_ACTIVE`; an old Fly secret with value `auth-only` is harmless
and can be removed later. Existing secrets and Cloudflare rules are reused.
Deployment restarts invalidate sessions as before.

Run `cd backend && mvn test package`, then from the repository root run
`python3 scripts/test-auth-boundary.py --jar backend/target/irv-0.1.0.jar`.
The HTTP script uses local test ingress evidence, never live credentials, and tests
no-profile, named-profile, legacy-name and mixed-profile launches; missing/wrong
secret, invalid/revoked certificate evidence; route isolation; status redaction;
secure cookies; and audit pairing. The JUnit suite also covers the OIDC flow,
registration and profile-independent bean isolation.

Live Cloudflare and Safari passkey verification is still required after deploying.
