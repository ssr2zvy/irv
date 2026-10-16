# Auth-only testing

The preparation-only Auth0/password-AND-passkey page has been replaced by the ZITADEL passkey login and certificate-bound session implementation.

Use [ZITADEL login and testing instructions](zitadel-login.md) for current settings, trusted headers, admission format, session behavior, test commands and remaining live acceptance checks.

The `auth-only` profile still disables legacy authentication, sandbox provisioning, workers, database, Tigris, terminal/log/preview endpoints and the old SPA. `/api/health` remains internal on port 8080; the diagnostic/login listener is loopback port 8081. Missing authentication configuration denies login.
