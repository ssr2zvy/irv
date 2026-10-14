# Costs

## Fly.io

**Billing:** [Fly.io dashboard](https://fly.io/dashboard) → the organization named by `FLY_ORG` → Billing.

Fly hosts the irv backend and frontend together, and the separate sandbox Machines. Charges include running compute, retained root filesystems on stopped/suspended Machines, provisioned volumes even when detached, applicable networking, snapshots, certificates, and build resources. Destroying a Machine does not delete every associated billable resource. The backend stays running even when every sandbox is idle. Staging and daily live canaries also use billable resources.

**Pricing:** [Fly resource pricing](https://docs.fly.io/about/pricing).

## Tigris

**Billing:** Your Fly organization billing page when provisioned through the Fly extension; otherwise your Tigris account billing dashboard.

Tigris stores incremental workspace chunks. Charges depend on retained object storage and requests under your plan. Backups retain chunks needed by older snapshots; deduplication does not make storage free. The backend proxies uploads/restores, so include applicable backend network costs too.

**Pricing:** [Tigris pricing](https://www.tigrisdata.com/pricing/).

## PostgreSQL

**Billing:** Your selected PostgreSQL provider's dashboard; Fly billing if hosted there.

The database stores accounts, invites, configuration, snapshot manifests, logs, usage, and optimizer history. Budget for the database service, storage, backups, and any applicable transfer. No managed database subscription is selected or provisioned by the ZIP. Local H2 and Compose PostgreSQL use your own machine's resources.

## GitHub Actions and container packages

**Billing:** [GitHub billing and usage](https://github.com/settings/billing), or the owning organization's billing page.

Actions runs tests, daily image rebuilds/scans, and deployments. GHCR hosts images. Applicable charges depend on your plan, repository/package visibility, Actions minutes, artifacts, and package storage/transfer. Daily rebuilds continue even when nobody opens a sandbox. Configure artifact and old-image retention in GitHub.

## Twilio Verify — optional

**Billing:** [Twilio Console](https://console.twilio.com/) → Billing.

Twilio provides SMS second-factor verification when configured. Charges depend on verifications and SMS delivery under your account's pricing. TOTP enrollment in Microsoft Authenticator or Duo Mobile uses no paid provider API in irv.

**Pricing:** [Verify pricing](https://www.twilio.com/en-us/verify/pricing).

## Domains and development subscriptions

**Billing:** Your domain registrar, DNS provider, and any development-tool accounts you choose.

Budget for the preview domain and its renewal, plus any paid DNS/TLS services. Codespaces, Copilot, Claude, or other development subscriptions are optional and are not required runtime dependencies of irv.

## What the app's cost figure means

Administration prices feed a sandbox cost estimate, not a complete invoice. Enter rates applicable to your region/account and reconcile them with provider bills. Shared backend, database, CI, domains, and optional SMS costs must also be counted for an end-to-end total. The optimizer neither guarantees the cheapest possible result nor automatically imports every provider's billing rates.

Pricing references checked 2026-10-14. This file intentionally contains no fixed dollar estimates that could be mistaken for your account's rates.
