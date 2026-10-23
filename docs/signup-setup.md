# Signup setup after this patch

Deploy the patch first. Then configure ZITADEL and Fly secrets. No invite-generation script or local terminal is required for the setup below.

## 1. ZITADEL service account

Open the administrator console for https://irv-instance-4ftbgu.us1.zitadel.cloud and select organization **irv-users**, ID **395285989452532411**.

1. Under Users / Service Accounts, create a dedicated service account for irv enrollment (or reuse an existing dedicated enrollment account).
2. In the organization's administrator membership controls, add that service account with **ORG_USER_MANAGER**. This is an administrative membership, not the application's `irv_user` role. Scope it to irv-users; do not use an instance-owner setup credential as the runtime token.
3. Open the service account's Personal Access Tokens section, create an expiring PAT and copy the token. Store its expiry so you can rotate it before expiry.
4. In project **395285999653022010**, confirm role key **irv_user** exists. Keep the existing passkey-only login policy and OIDC configuration. Public provider registration does not need to be enabled.

Official references: [service accounts and API access](https://zitadel.com/docs/guides/integrate/zitadel-apis/access-zitadel-apis), [administrator roles](https://zitadel.com/docs/guides/manage/console/administrators).

## 2. Choose your invite string

You define the value yourself: **43–128 characters**, using ASCII letters, digits, hyphens (`-`) and underscores (`_`). It is case-sensitive; no spaces, trimming or decoding. Choose an unpredictable value. A password manager is optional; no generator script, JSON, hash or expiry date needs to be supplied.

## 3. Fly secrets for iruvu

After deploying this patch, open **Fly → iruvu → Secrets**.

| Secret | Value |
|---|---|
| `IRV_ZITADEL_ENROLLMENT_TOKEN` | Your service-account PAT, without a `Bearer ` prefix |
| `IRV_AUTH_INVITE_CODE` | Your literal chosen invite string, without surrounding quotes |
| `IRV_AUTH_ENROLLMENT_ENABLED` | `true` |

Remove the old `IRV_AUTH_INVITE_CODES_JSON` and `IRV_AUTH_INVITES_JSON` secrets when switching to this mode. Nonempty JSON invites combined with the new single-code secret intentionally fail startup, preventing old invitations from accidentally remaining active. Empty defaults `[]` and `{}` are harmless. Existing JSON-only configuration remains supported when the single-code secret is absent.

Apply the changes together if possible. If editing secrets individually: set enrollment to `false`, remove the old invite settings, add the PAT and single-code secret, then set enrollment to `true` last. Each applied update restarts the app and signs current users out. No further source deployment is necessary to rotate a code.

Keep the existing Cloudflare certificate/header rules, `TUNNEL_TOKEN`, `IRV_AUTH_INGRESS_SECRET`, and mounted `/data` volume. No signup secret goes into Cloudflare.

## 4. What the application does automatically

- Hashes your exact string with SHA-256; the application lookup and ledger use its hash.
- Gives a new code **2 hours from its first enabled application startup**. It records that fixed expiry in `/data/auth/invites`.
- Redeploying, restarting, disabling and re-enabling signup, or removing and re-adding the same code does not restart its expiry.
- Reserves it at the beginning of account provisioning. Only one signup can claim it; restart/deployment cannot make it reusable.
- A different code gets its own 2-hour window when loaded by an enabled app. The previous code stops being accepted once the new configuration is applied.
- A previously consumed code remains consumed even when migrated from JSON configuration. An unused JSON code moved into single-code mode begins its first automatic 2-hour window; use a fresh string for a new invitation.

You do not need to remove a consumed code. To invite another person, replace it with a different string and apply the secret update. To withdraw an unused invitation early, remove the secret or set enrollment to `false` and apply. To renew an expired invitation, choose a different string.

## 5. Sign up

Open https://iruvu.space with your approved certificate, choose **Create account**, and enter the **same original string** plus the account details. Complete **Set up passkey** at ZITADEL, return to irv, and select **Sign in**. The provider may request an account/username before its passkey prompt. Use the bottom-right lock to inspect authentication status. Enrollment itself does not create an authenticated irv session. Other unfinished application endpoints remain locked.

## Troubleshooting and persistence

Malformed settings fail startup without displaying secret values. A missing provider token, absent invite, expired invite or explicit disable prevents signup availability. Inspect the safe `signup_availability` audit event for configuration problems. The button's visibility does not prove provider permissions or that a configured invite remains unused; the backend always enforces the durable reservation.

A provisioning failure may leave the invite reserved because a provider account might already exist. Review the account and audit stage; do not erase the ledger to retry. Preserve the single existing Machine and `/data` volume across deployments. Independent ledgers, deleting the volume, or restoring an old backup invalidate the persistent single-use/expiry guarantees.

The original string remains readable to anyone with access to Fly's secret/environment values; the backend does not log it. Hashing does not strengthen a predictable string. First live signup, ZITADEL permissions and Safari passkey behavior still require verification after deployment.

Existing 24-hour single-code records are shortened once to two hours from their original activation. Codes already older than two hours immediately expire. Consumed codes remain consumed.
