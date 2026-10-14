# Automatic maintenance

Included from the initial version:

- `.github/workflows/ci.yml`: unit/integration tests, local backend-agent smoke, UI build, and Docker builds.
- `maintenance.yml`: daily clean base-image rebuilds, SBOM, Trivy checks, current Fly OpenAPI compatibility checks, immutable candidate publication, staging canary, then production catalog promotion.
- `.github/dependabot.yml`: daily Java, npm, Python, image, and GitHub Action dependency checks.
- `dependency-merge.yml`: enables auto-merge for Dependabot patch/minor changes only. **Configure branch protection requiring the test job first.** Without that gate, do not enable auto-merge. Major upgrades remain reviewable PRs.
- `deploy.yml`: after successful main-branch CI, deploy the backend to staging, run credentialed smoke, and deploy the passed commit to production.
- `renovate.json`: optional alternative to Dependabot; enable only one dependency-update bot.
- Backend daily cleanup for old logs, sessions, snapshots, and unreferenced chunks.

## One-time GitHub setup

Push this repository to GitHub; enable Actions, Dependabot, branch protection and auto-merge. Give the workflow permission to publish GHCR packages. Publish sandbox packages so Fly can pull them, or provide a supported private-registry mechanism separately.

Configure these Actions secrets:

- `IRV_SMOKE_ORIGIN`: HTTPS URL of a staging irv backend.
- `IRV_SMOKE_USERNAME`, `IRV_SMOKE_PASSWORD`, `IRV_SMOKE_TOTP`: dedicated staging account with authenticator secret. Do not use a human administrator account.
- `IRV_STAGING_MAINTENANCE_TOKEN`: matches staging `IRV_MAINTENANCE_TOKEN`.
- `IRV_PRODUCTION_ORIGIN`, `IRV_PRODUCTION_MAINTENANCE_TOKEN`: production promotion endpoint and matching token.
- `FLY_DEPLOY_TOKEN`, `IRV_STAGING_APP`, `IRV_PRODUCTION_APP`: backend deployment.

The staging instance must already have working Fly, database and Tigris configuration. The tests create billable canaries, then archive them after verified saving. Run cleanup manually if a workflow is interrupted before its finally block. Provision a separate staging organization where possible.

## What promotion means

Successful maintenance updates the backend's image catalog with digest-pinned references. The new images are used at the next cold creation. Existing active or suspended environments continue on their current image until recreated; the worker does not interrupt active development or change a running user's filesystem.

Failed scans or canaries block production promotion. GitHub marks the workflow failed and retains test/SBOM/schema artifacts. Fixable HIGH/CRITICAL vulnerabilities block promotion. Unfixed vulnerabilities are visible in reports; rebuilding cannot invent a patch.

The API checker compares endpoint presence and newly required schema fields. A breaking API change blocks updates for adapter review. It does not synthesize arbitrary correct Java fixes. The live smoke detects integration issues that schema comparison misses. A false-positive schema change requires a reviewed baseline update to `contracts/fly-openapi.json`.

Rollback: promote the previous recorded pair of image digests using `scripts/promote.py`. Backend rollout uses Fly health checks; a successfully deployed version with a later behavioral regression requires explicit rollback to its prior image. Autonomous diagnosis/rollback of every future behavioral regression is not implemented.

GitHub's scheduled execution is the daily worker; no ChatGPT reminder or external scheduled task is needed. These workflows are packaged, not installed into your account by downloading the ZIP.
