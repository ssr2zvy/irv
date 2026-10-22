# Signup setup after this patch

Deploy the patch first. Then configure ZITADEL and Fly secrets. No invite-generation script or local terminal is required for the setup below.

## 1. ZITADEL service account

Open the administrator console for https://irv-instance-4ftbgu.us1.zitadel.cloud and select organization **irv-users**, ID **395285989452532411**.

1. Under Users / Service Accounts, create a dedicated service account for irv enrollment (or reuse an existing dedicated enrollment account).
2. In the organization's administrator membership controls, add that service account with **ORG_USER_MANAGER**. This is an administrative membership, not the application's `irv_user` role. Scope it to irv-users; do not use an instance-owner setup credential as the runtime token.
3. Open the service account's Personal Access Tokens section, create an expiring PAT and copy the token. Store its expiry so you can rotate it before expiry.
4. In project **395285999653022010**, confirm role key **irv_user** exists. Keep the existing passkey-only login policy and OIDC configuration. Public provider registration does not need to be enabled.

Official references: [service accounts and API access](https://zitadel.com/docs/guides/integrate/zitadel-apis/access-zitadel-apis), [administrator roles](https://zitadel.com/docs/guides/manage/console/administrators).

## 2. Create an invite using a password manager

Generate a fresh random string of **43–128 ASCII characters**, using only `A–Z`, `a–z`, `0–9`, `_` and `-`. A password manager's random 48-character alphanumeric password works. Do not use a memorable phrase, reuse the ingress secret, or copy a sample code. Validation checks format, not randomness.

Choose a future expiry in UTC, ending in `Z`, such as `2026-12-01T00:00:00Z` (replace with your intended future date).

## 3. Fly secrets for app iruvu

In the Fly dashboard, open **iruvu → Secrets**. Add/update these three settings, preferably in one save/apply operation:

| Secret name | Value |
|---|---|
| `IRV_ZITADEL_ENROLLMENT_TOKEN` | The literal service-account PAT; no `Bearer ` prefix or surrounding quotes |
| `IRV_AUTH_INVITE_CODES_JSON` | The full JSON array shown below, with your generated code and expiry substituted |
| `IRV_AUTH_ENROLLMENT_ENABLED` | `true` |

JSON FORMAT ONLY — replace the placeholder; it is intentionally not a valid invite:

```json
[
  {
    "code": "<PASTE-YOUR-RANDOM-CODE-HERE>",
    "expiresAt": "2026-12-01T00:00:00Z"
  }
]
```

Paste JSON directly as the secret value, without Markdown fences or outer shell quotes. If the dashboard saves one secret at a time, explicitly set enrollment to `false` first, add token and invites, then set it to `true` last. Apply the changes so the Machine restarts with the new environment. Every restart signs existing users out.

Existing `TUNNEL_TOKEN`, `IRV_AUTH_INGRESS_SECRET` and Cloudflare header/certificate rules stay as configured. Keep the existing `/data` volume and `/data/auth/invites` ledger; no new database or volume is required. No signup secret belongs in Cloudflare. Authentication applies in all profiles.

`IRV_AUTH_INVITES_JSON` remains the optional legacy hash-to-expiry object. Both settings can coexist, with at most 1000 distinct invites combined. The same code in both formats must have exactly the same expiry instant; conflicting expiries fail startup. When migrating, retain any other unused hashed invitations or intentionally withdraw them. Removing a code from only one setting does not withdraw it if the other still contains its hash.

## 4. Use the invite

1. Open https://iruvu.space with your approved certificate.
2. Choose **Create account** and enter the original invite code, username, email, given name and family name.
3. Open **Set up passkey** and finish ZITADEL enrollment.
4. Return to irv and choose **Sign in**. The button starts the existing hosted ZITADEL login flow. ZITADEL may first request a username/account choice before the passkey prompt.
5. Use the bottom-right lock to view authentication status. Successful enrollment alone does not create a signed-in irv session. Other application endpoints remain locked.

## Subsequent invites and troubleshooting

Add another object to the JSON array and preserve other unused entries. Give the new original code privately to the intended person. Replacing a secret replaces its whole list. Removing entries withdraws unused invites; re-adding or extending a consumed code never makes it usable again. Generate a different code for a new invitation.

The backend converts codes to SHA-256 hashes at startup and stores only hashes in its invite lookup and durable redemption ledger. Plaintext remains in Fly's secret/environment configuration; anyone with access to those values can read an unused code. It is never intentionally included in application logs or parser exception chains.

Malformed configuration fails startup with a setting-specific, value-free error. Expired invites do not enable signup. If Create account is absent, check the backend `signup_availability` audit event: provider token missing, invites not configured, invites expired, or enrollment disabled. Availability does not prove provider credentials work or that every listed invite is unused.

A provider failure can leave an invite reserved for review because an account may already have been created. Inspect the safe audit stage and review the user in ZITADEL; do not delete the ledger or repeatedly retry the same invitation. This patch does not validate live provider permissions, provider policy, or Safari passkeys automatically. The first real signup is the end-to-end check.
