# Manual account and invitation operations

This runbook is for the current single-Machine, `auth-only`, Flow 2 deployment.
There is no permanent certificate/account mapping. Certificates gate admission
and bind individual login transactions and sessions. Payment is out of scope.
These are operator instructions, not actions performed by the patch.

## List accounts

Open https://irv-instance-4ftbgu.us1.zitadel.cloud/ui/console as your administrator.
Select `irv-users` (organization `395285989452532411`), then Users. Inspect human
users separately from the enrollment service account. Record the immutable user
ID, username, status and project role assignment. For project `irv`
(`395285999653022010`), inspect role assignments (also called authorizations or
user grants) for `irv_user`. Existence of a user alone does not grant irv access.
Keep the administrator in `irv-org`; do not grant application users admin roles.
Usernames and email addresses are not stable account identifiers.

## Revoke account access

1. Identify the exact user ID in `irv-users`. Remove its `irv_user` assignment for
   project `irv`. Optionally deactivate the user as a broader provider-side block.
2. Verify that the role removal has taken effect before reopening access. Roles
   are checked at login, not continuously fetched from ZITADEL on each request.
3. Restart the current application Machine to discard ALL in-memory sessions and
   pending login transactions. This logs everyone out, not just the revoked user:

   ```sh
   fly machine list -a iruvu
   fly machine restart MACHINE_ID -a iruvu
   ```

4. Verify the old cookie cannot reach `/auth/protected`, and a new login for the
   revoked user fails admission. Keep access closed while investigating if the
   role still appears in new tokens. For urgent containment, close application
   ingress first while completing these steps.

A role removal alone does not kill an existing local session. A restart alone
permits users with the role to log back in. Both steps are required. This runbook
does not implement targeted session revocation or provider event synchronization.
Do not delete the account merely to revoke access; preserve its immutable ID.
If the deployment ever has multiple application processes, clear every process's
sessions; the current single-Machine ledger/session design must be redesigned
before scaling. Cloudflare certificate revocation is separate and affects all
users relying on that certificate, because Flow 2 certificates are not owned by
accounts.

## Recover a lost account (operator only)

You personally authorize recovery after independently verifying the person's
identity and the exact existing user ID. No self-service email, SMS, password or
recovery-code bypass is authorized by irv.

Remove the role and invalidate application sessions as above. In ZITADEL, remove
lost/compromised passkeys as appropriate and initiate a fresh passkey registration
for the SAME user using the provider's administrative passkey enrollment flow.
Deliver its short-lived setup link only to the person you verified. Treat that
link as a credential; do not record it in git or logs. Restore `irv_user` only
after the replacement passkey is enrolled and test fresh login. Do not create a
replacement user or redeem a normal signup invite to recover an existing account.
Do not change `irv-org` administrator credentials as part of ordinary user recovery.

Provider configuration still needs live verification: recovery and alternate
login paths must not circumvent the passkey-only policy. This documentation does
not assert that those hosted settings have already been verified. Replacing a
lost client certificate requires no account mapping edit; revoke the old
certificate at Cloudflare, install an accepted replacement, and start a new login.

## Issue and withdraw invites

`IRV_AUTH_INVITES_JSON` is a complete SHA-256-hash-to-UTC-expiry JSON object in Fly
Secrets. Raw invitation codes go only to invitees. The persistent H2 ledger at
`IRV_AUTH_INVITE_DB` (default `/data/auth/invites`, physical file
`/data/auth/invites.mv.db`) prevents reuse. Keep its volume across deployments.

Generate an invite with the existing offline script, outside the repository:

```sh
python3 scripts/create-auth-invite.py --output /secure/operator/invite-001 --hours 24
```

The output directory must not already exist; its parent must exist. To retain
other invites, pass `--existing-json /secure/operator/current-invites.json`.
Keep your authoritative current JSON privately: replacing the secret with only
one new entry withdraws all other entries. Never print raw codes in deployment
logs or commit the generated directory.

After reviewing the full intended JSON, import without putting its value in a
command argument (run from the operator's trusted machine):

```sh
python3 - /secure/operator/invite-001/fly-invites.json <<'PY' | fly secrets import -a iruvu
import json, pathlib, sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
print('IRV_AUTH_INVITES_JSON=' + json.dumps(value, separators=(',', ':')))
PY
```

This updates the secret and normally restarts the Machine, clearing sessions.
Use `--stage` on `fly secrets import` only when deliberately postponing application
of the change until the next deployment. A staged withdrawal is not immediate.
Secrets persist: do not reissue invites on every deployment. Signup also needs
`IRV_AUTH_ENROLLMENT_ENABLED=true` and the scoped enrollment PAT described in
[Flow 2 setup](flow2-enrollment.md).

To withdraw an unused invite, remove its hash from your private complete JSON and
import the revised object. To withdraw all unused codes, import `{}`. Removal
cannot undo a reservation/account already created. Disable enrollment or close
access first if you need to stop in-flight signup as well. Never delete ledger
rows or reset the volume to recycle a code. Issue a new code instead.

## Review consumed or interrupted invites

Ledger states come from the implementation:

| State | Meaning and operator action |
|---|---|
| `RESERVED` | Claimed before contacting ZITADEL; a crash/timeout may leave uncertain provider state. Investigate. |
| `USER_CREATED` | User ID recorded; role/enrollment may still be incomplete. Inspect that user. |
| `ISSUED` | Role and passkey setup link issued; does NOT prove passkey enrollment completed. |
| `REVIEW_REQUIRED` | Provisioning failed or became uncertain; inspect provider users and audit events before retrying anything. |

Every row blocks reuse, regardless of status. The ledger stores hashes, timestamps,
status and possibly user IDs, never certificates, raw codes or enrollment links.
For review, take a consistent backup using H2's supported backup facility or stop
the application before copying the database. Do not copy a live database file or
open a second writer on production. Inspect an offline COPY with the matching H2
version from `backend/pom.xml`'s resolved dependencies, in read-only mode
(`ACCESS_MODE_DATA=r;IFEXISTS=TRUE`). For example, in H2 Shell:

```sql
SELECT code_hash, claimed_at, status, subject
FROM invite_redemptions ORDER BY claimed_at DESC;
```

`claimed_at` is epoch milliseconds. No public ledger/admin endpoint is added.
If the row lacks a subject, correlate the attempted username and provider audit
events; absence of a recorded ID is not proof no account was created. Repair the
existing account manually or revoke its access; issue a fresh invite only when a
new account is actually intended. Keep the old reservation intact.

## References

- [ZITADEL users](https://zitadel.com/docs/guides/manage/console/users-overview)
- [ZITADEL role assignments](https://zitadel.com/docs/guides/manage/console/roles)
- [Fly secret import](https://fly.io/docs/flyctl/secrets-import/)
- [Fly Machine restart](https://fly.io/docs/flyctl/machine-restart/)
- Local source: `AuthSettings`, `Enrollment`, `InviteLedger`, `BoundSessions`, `LoginPolicy`.
