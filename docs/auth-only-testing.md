# Auth-only testing

The auth-only profile runs ZITADEL passkey login, invite-gated signup and temporary certificate-bound sessions (Flow 2). See [Flow 2 enrollment](flow2-enrollment.md) for current behavior and [trusted forwarding](zitadel-login.md) for Cloudflare headers.

Legacy authentication, sandbox provisioning, workers, Tigris, terminals/logs/previews and the old SPA remain excluded. The legacy database and migrations stay disabled; enrollment explicitly opens a separate small H2 invite ledger on the existing /data volume when enabled. No permanent certificate/account registry is stored.

/api/health remains internal on port 8080; diagnostics and selected authentication routes use loopback port 8081. Signup defaults disabled until its token and invite settings are supplied. Certificate admission defaults required. Every deployment invalidates in-memory login transactions and sessions, while consumed invitations persist on the volume.
