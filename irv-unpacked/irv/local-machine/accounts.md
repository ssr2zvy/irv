# Accounts

Record resource identifiers here after provisioning. Keep passwords, tokens, invite values, and authenticator secrets in the stores listed in [tokens.md](tokens.md). The entries below are blanks to fill, not resources already created by this ZIP.

| Service | Dashboard | Record for your deployment |
| --- | --- | --- |
| Fly.io | [Fly dashboard](https://fly.io/dashboard) | Account/organization owner, `FLY_ORG`, staging and production app names, region |
| Tigris | [Tigris console](https://console.tigris.dev/) or the Fly storage dashboard | Bucket per environment, owning account, whether billing is through Fly |
| PostgreSQL | Your selected provider | Project/cluster name, database name, region, backup owner; connection settings in deployment configuration |
| GitHub | Your irv repository → Settings / Actions / Packages | Repository owner/name, package names, staging/production workflow configuration |
| Twilio (optional) | [Twilio Console](https://console.twilio.com/) | Account/project and Verify service owner |
| Domain/DNS | Your registrar and DNS dashboards | App domain, separate preview domain, DNS provider, renewal owner |

For production previews configure wildcard DNS/TLS for subdomains of `IRV_PREVIEW_ORIGIN`. Keep the preview origin on a different registrable domain from the main app. Staging should have its own database, bucket, keys, and promotion token so canaries cannot overwrite production data.

Sandbox app identifiers are generated dynamically and recorded by irv. Do not maintain a manual list here. Keep one control-plane backend replica as described in [deployment](../docs/DEPLOYMENT.md).
