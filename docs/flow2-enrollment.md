# Flow 2: invite signup and session-only certificate binding

## Implemented behavior

Any certificate accepted by the configured Cloudflare mTLS gate may accompany any admitted account's login. No permanent certificate-to-user map or per-account certificate configuration exists. A fresh random servlet session is bound to the verified ZITADEL identity and the exact certificate presented for that login. Another valid certificate cannot reuse that cookie or finish its OAuth transaction. Two fresh sessions using the same certificate may authenticate different users.

Signup: valid ingress/certificate -> invite + username + separate email + provider-required given/family names -> reserve invite durably -> create a new ZITADEL human without a password -> obtain a one-time hosted passkey enrollment link -> assign the irv_user project role -> show enrollment link. Open it on the iPhone, enroll the passkey, then return to the irv tab and select Sign in. ZITADEL's hosted login accepts the username and verifies the passkey. An enrollment response alone never creates an authenticated irv session.

The hosted enrollment page opens in another tab. Automatic return from enrollment is NOT implemented; the explicit return-and-sign-in step is intentional and displayed in the UI. This keeps enrollment on the provider's passkey domain, so hosted login can use the resulting passkey. No JavaScript WebAuthn credential is created for the wrong iruvu.space relying-party domain.

Username is a login label. The permanent account key is the verified OIDC issuer plus immutable subject, not username or email. The issuer is fixed in AuthSettings. Email is required by the provider and administratively marked verified to avoid an email-code step, as previously chosen. That flag does not prove mailbox ownership; irv never grants access, links accounts, or recovers accounts based on email. No password is created. Signup never enrolls a credential onto an existing account.

## Deployment settings

Keep the existing single Fly Machine, /data volume, tunnel token, ingress secret, Cloudflare rejection/overwrite rules and mandatory authentication in every profile. No deploy.sh, workflow, Containerfile, public IP or extra application is needed.

| Setting | Value / purpose |
|---|---|
| TUNNEL_TOKEN | Existing tunnel secret |
| IRV_AUTH_INGRESS_SECRET | Forwarding secret of 32-256 printable ASCII characters (U+0021-U+007E), with no whitespace |
| IRV_AUTH_CERT_REQUIRED | Defaults true; keep true now |
| IRV_AUTH_ENROLLMENT_ENABLED | Optional override: false disables signup; when omitted, a configured provider token and invites enable it automatically |
| IRV_ZITADEL_ENROLLMENT_TOKEN | New dedicated, expiring ZITADEL service-account PAT with ORG_USER_MANAGER membership ONLY in irv-users (395285989452532411) |
| IRV_AUTH_INVITE_CODE | Recommended: your literal 43–128 character code; automatic persistent 2-hour expiry and single use; no JSON. See [signup setup](signup-setup.md). |
| IRV_AUTH_INVITE_CODES_JSON | JSON array of plaintext code/UTC expiresAt entries; backend hashes codes automatically. See [signup setup](signup-setup.md). |
| IRV_AUTH_INVITES_JSON | JSON object mapping SHA256 of each high-entropy invite to its ISO UTC expiry |
| IRV_AUTH_INVITE_DB | Defaults /data/auth/invites, the file prefix for the durable H2 ledger |

Remove obsolete IRV_AUTH_ADMISSIONS_JSON and IRV_AUTH_ALLOWED_CERT_SHA256 secrets. This implementation does not read them; leaving their values does not retain the old restrictions. No certificate fingerprint needs to be entered into Fly.

Set the identical literal ingress-secret value in Fly's `IRV_AUTH_INGRESS_SECRET` and Cloudflare's `X-Irv-Ingress-Key` request-header Transform Rule. Do not trim, decode, normalize or change its case on either side. Use cryptographically generated randomness; merely matching the allowed character and length format does not make a value random. A 64-character hexadecimal value remains valid and can optionally be generated with `openssl rand -hex 32`. Existing values need no change when they meet the new format and match exactly on both sides. Certificate SHA-256 fingerprints are separate and must still be exactly 64 hexadecimal characters.

Do not reuse the IAM_OWNER setup PAT as the runtime enrollment token. Use a dedicated service account with ZITADEL administrative membership ORG_USER_MANAGER scoped to irv-users, then create its expiring PAT. You can repurpose the existing irv-upa setup service account: first add the scoped role, remove its IAM_OWNER membership while retaining your separate human administrator, and rotate its PAT. This avoids requiring another service-account slot. This is a ZITADEL administrative role, NOT the irv_user project role. Source permission definitions include user.write, user.read and user.grant.write. The runtime token can manage users in this organization; leaking it remains serious. This patch does not automate service-account creation or change provider policies.

Set the token and invite JSON through the existing Fly Secrets interface on iruvu, then enable enrollment and deploy. Missing token with enrollment enabled fails startup; no invites means no signup. Existing authorized passkey users can still log in with signup disabled. Do not put token values in source, GitHub issues, screenshots, or command arguments.

## Configure invites without a script

Use `IRV_AUTH_INVITE_CODE` as described in [signup setup](signup-setup.md). Supply your own unpredictable string; the backend hashes it and persists its 2-hour expiry on first enabled startup. Restarting does not renew it. Remove nonempty JSON invite settings when using this mode. The older JSON formats remain available as an alternative, not alongside the single-code secret.

## Optional script: generate and issue a hash-only invite

On a trusted development terminal (including the repository Codespace), run:

```sh
python3 scripts/create-auth-invite.py --output /tmp/irv-new-invite --hours 24
```

Use a new output directory for each run. The generator makes a random 256-bit URL-safe code and writes files with owner-only permissions. It makes no network calls and does not print the code into terminal logs.

1. Copy the complete JSON from fly-invites.json into the Fly secret IRV_AUTH_INVITES_JSON.
2. Give invite.txt's code to the intended registrant privately. Paste it into irv's signup form.
3. When adding another invite, retain other unused entries in the JSON (or supply --existing-json to the generator). Replacing the secret without those entries withdraws the omitted invitations. Previously consumed hashes stay consumed even if re-added.

Example FORMAT ONLY; generate real randomness rather than using this placeholder:

```json
{"<64 lowercase SHA256 hex characters>":"2026-12-01T00:00:00Z"}
```

Invites are always required in this patch. Removing certificates does not remove invites. A future gradual public-registration policy is not implemented as an accidental bypass switch.

## Persistence, concurrency, and partial failures

The dedicated H2 file stores invite hashes, claim times, provisioning status and created provider subject for operational review. It stores NO raw invite codes, certificate fingerprints, certificate/account mappings, passwords, passkeys, OAuth tokens or enrollment links. It uses the existing volume and does not activate the legacy database/Flyway/schema.

The primary key and committed reservation allow exactly one request to claim a code, even across application restarts. Single-Machine deployment only: do not start independent copies with separate ledgers or restore an old ledger while retaining invitation secrets. That would resurrect old invite state. Back up the ledger and withdraw invitation hashes if restoring an older backup.

Provider creation, enrollment-link generation and role assignment are separate remote operations, not one distributed transaction. No blind retries or automatic release of a reservation occur after failure. A provider timeout may have created a user even if no response arrived. The UI reports this as requiring operator review, never as successful signup. Inspect the organization's users and grants before issuing a new invite; disable/delete an incomplete unwanted user as appropriate. Do not simply erase the reservation and retry a used code.

After a successful response, Resume passkey setup can return the same link for 15 minutes, ONLY from the same browser session and certificate. The link is held in memory; restart, sign-in, cookie loss or expiry removes this convenience. This 15-minute window does not override the provider's own enrollment-code lifetime. Do not share the link. Abandoned enrollment beyond that point needs operator review and a new invite, not an account-recovery endpoint. Successful enrollment is confirmed by subsequent verified passkey login, not by the ledger status ISSUED.

Signup attempts are limited to 30 per 15-minute process-wide window, including rejected invites. Maximum three provider mutations per successful attempt; no retry loop. This cap resets on application restart and is an application safeguard, not a billing guarantee. Existing Cloudflare protections remain relevant.

## Login, session security, and rollout

Login uses the existing hosted authorization-code + S256 PKCE flow: fresh login, state, nonce, verified RS256 ID-token signature/issuer/audience/expiry, auth_time, passkey evidence and irv_user role in the configured organization. Browser requests use Secure/HttpOnly/SameSite=Lax __Host-irv cookies, not browser-stored JWTs. Changing certificates mid-session fails. Sessions expire after 5 minutes idle or 8 hours absolute, whichever comes first. Background polling does not extend idle time. Signup and resume are POST-only, CSRF- and exact-Origin-protected, and unavailable on health port 8080.

No local account whitelist exists. Ordinary accounts get the role through invited signup; a provider administrator can also deliberately grant it. Keep native public provider registration disabled, external providers/OTP disabled and the previously configured passkey policy in place. Password-only tokens are rejected. The source-reviewed amr interpretation (user + mfa) depends on those provider policies; it is not universal passkey attestation.

Later, set IRV_AUTH_CERT_REQUIRED=false and remove the Cloudflare certificate rejection rule as a coordinated deployment. Keep the private tunnel and ingress-secret overwrite. Certificate headers are then optional and ignored for binding, but invites, role checks, PKCE, passkey evidence and session checks remain. Restart clears old sessions. Removing certificates removes their protection against copied-cookie reuse; invites do not replace it.

To revoke an account, remove its provider role and restart irv to discard active local sessions. Provider role changes alone are not instantly reflected in existing sessions. Certificate revocation is enforced at Cloudflare; no automatic provider-session-revocation webhook is introduced. Sandbox APIs, terminals, logs and previews remain disabled in auth-only. ML, automatic upgrades, payment and sandbox authorization are outside this patch.

## Validation and remaining live checks

Local tests cover real signed mock-provider OAuth/PKCE login, bad tokens and replay, session/certificate switching, CSRF, route isolation, invite concurrency/restart reuse, provisioning request shape, failed provider calls, enrollment resume and certificate-disabled invite enforcement. See the patch's VALIDATION.md for actual execution results.

Still required on the deployed iPhone: invite -> username signup -> hosted passkey enrollment -> return -> login -> protected probe. Confirm the hosted Login V2 route, service-account scope, role claim, username policy and actual provider AMR on this instance. Local tests are NOT proof that these live settings already exist. Also test denied missing/revoked certificate, reused invite, wrong-cert cookie, idle expiry and logout. No live provider changes or deployment were made while producing this patch.

## API/source references and costs

- Create user: https://zitadel.com/docs/reference/api/user/zitadel.user.v2.UserService.CreateUser
- Enrollment code: https://zitadel.com/docs/reference/api/user/zitadel.user.v2.UserService.CreatePasskeyRegistrationLink
- Role grant: https://zitadel.com/docs/reference/api/management/zitadel.management.v1.ManagementService.AddUserGrant (supported V1 compatibility endpoint, documented as deprecated in favor of authorization V2)
- Reviewed provider source: https://github.com/zitadel/zitadel/tree/78cd78ace5f047133b21ffec31a3c21f005616e9 — apps/login/src/app/(login)/passkey/set/page.tsx, apps/login/src/components/register-passkey.tsx, proto/zitadel/user/v2/user_service.proto, cmd/defaults.yaml.

No payment, plan upgrade, trial activation, custom-domain purchase or billing endpoint is called. Signup now DOES use administrative APIs (three calls for a successful signup), unlike the earlier login-only patch. Existing ZITADEL account/DAU/API limits and Fly/domain costs still apply. No new hosted database or machine is introduced; the existing volume stores a small ledger. This is not a guarantee about the current subscription settings on your provider account.

## Operator runbooks

See [manual account and invite management](manual-auth-operations.md) and
[default endpoint authentication](default-authentication.md).
