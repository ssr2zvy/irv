# Authentication UI and login handoff

Based on IRV main 19218d03745958f8ecceb76c3424e930f11b4282 (PR #38).
Visual reference: Telugu-now main `upa/frontend/src/settings/SettingsShell.tsx`,
`upa/frontend/src/styles/settings-layout.css` and `upa/frontend/src/styles/base.css`
(as fetched October 21, 2026). The implementation uses local inline SVGs and system
fonts; it adds no external scripts, font requests or UI dependencies.

The old login initiated a hidden form POST and returned an empty 302 to the
provider. A real HTTP reproduction confirmed that response, with CSP `form-action
'self'` and no attachment header. That conflicts with external form navigation in
browsers that apply form-action to redirected destinations. The reported Safari
download prompt itself has not been reproduced locally. The JSON handoff removes
that ambiguous form redirect while retaining the admission/CSRF/transaction checks.

## Signup configuration

The previous source defaulted `IRV_AUTH_ENROLLMENT_ENABLED` to false. That exact
state generated the message seen by the operator. The patch supports automatic
availability when provider and invite configuration exists, and keeps explicit
operator disabling. Missing configuration remains closed and is logged using
`signup_availability` reasons, without prose on the sign-in page.

Existing deployment setup still requires:
- `IRV_ZITADEL_ENROLLMENT_TOKEN`: dedicated organization-scoped enrollment PAT.
- `IRV_AUTH_INVITES_JSON`: configured invite SHA-256 to UTC-expiry map.
- Remove an explicit `IRV_AUTH_ENROLLMENT_ENABLED=false`, or set it to `true`, if
  signup is intended. Do not enable true without the provider token.

These are Fly runtime secrets, not frontend/build variables. Reuse existing values;
never paste them into GitHub or browser logs. This patch does not generate a
provider PAT, invent an invite, or inspect deployed Fly secret values. The exact
operator procedure and invite-ledger requirements are in `flow2-enrollment.md`.

## Checks

Run `cd backend && mvn test package`, then
`python3 scripts/test-auth-boundary.py --jar backend/target/irv-0.1.0.jar` from the
repository root. The HTTP script checks the JSON handoff, secure-cookie session,
Origin/CSRF/admission, status redaction and audit exclusion of the authorization URL.
JUnit tests exercise signed-token validation and the entire mocked-provider login.

Live verification: open the site on Safari, use the passkey action, confirm normal
provider navigation, complete login, and exercise invite-based enrollment. Check
application logs for the signup availability reason if the account menu
entry is absent. Other endpoints remain locked.
