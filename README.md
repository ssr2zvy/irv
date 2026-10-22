# irv

Invite-only browser development environments, with a Spring Boot control plane, React interface, Fly Machines, and incremental workspace storage in Tigris.

**After applying the sync and logs source patch, rebuild before running. The existing `dist/irv.jar` and checked-in frontend assets predate this patch. Run `bash scripts/build.sh` to refresh them, or deploy with the Docker source build. Rebuild both sandbox images for the new editing protocol.**

**This repository contains implementation source and a runnable application JAR.** Local verification is recorded in `docs/VALIDATION.md`. Live Fly/Tigris/SMS deployment and Docker image builds require your accounts and must pass the included staging tests before production use. No credentials are bundled.

## Current runtime

Authentication is mandatory in every Spring profile, including the default profile.
See [mandatory authentication](docs/mandatory-authentication.md) for current routes,
configuration, deployment and checks. `/` is the certificate-admitted sign-in/signup
page. Its bottom-right lock opens `/auth/diagnostics`.

Sandbox services, the old password/OTP login, old SPA, terminals and previews remain
disabled. The older feature descriptions below describe deferred implementation,
not currently exposed routes. Rebuild from source; checked-in JARs and historical
patch ZIPs are not current runtime artifacts.


## What is included

- Username/password authentication, expiring single-use signup invitations, mandatory TOTP or optional Twilio Verify SMS, one-use recovery codes, encrypted factor secrets, rate limits, session revocation, CSRF origin/header checks.
- A sandbox dashboard with browser PTY terminal, isolated-origin temporary previews, environment/process logs by session, startup and idle countdowns, configuration/script editing, directory shortcuts, and inspectable fixed-policy decisions.
- Restricted `config.toml`, one sourced environment entrypoint, helper scripts, named processes, optional file logs, shell directory functions. Infrastructure configuration stays in the backend.
- Two image recipes: a common blank Python/Bash/Git/agent image, and an extended JDK 17/Maven/Node 22 image. The common layers are reused through image inheritance.
- One Fly App and distinct private network per sandbox. Only an environment-specific bearer token is passed to the agent; Fly/Tigris/maintenance credentials remain in the control plane.
- Incremental fixed-size chunk snapshots of only the designated workspace. Hash verification, restore verification, file modes, timestamps, symlinks, empty directories, seven-day backup retention, and orphan-chunk collection.
- Revision-controlled TOML/script delivery with exclusive editing, recoverable drafts, and machine acknowledgements. Configuration can be edited while an environment is offline.
- A fixed five-minute idle policy: working volume during use, verified final incremental save and log flush, then Machine and volume deletion. Returns cancel pre-commit cleanup. Failed saves retain working resources. Recovery estimates above 180 seconds cause a retention exception.
- Adaptive cost optimization is deferred in the entirely commented `backend/src/main/java/dev/irv/DeferredCostOptimizer.java`; telemetry and configured-rate cost reporting remain active.
- Manual build, test, image-publication, and deployment tools. The daily sandbox-environment maintenance worker is deferred; its requirements are recorded in the comment-only `backend/src/main/java/dev/irv/EnvironmentMaintenanceWorker.java`. Automatic image updates and Fly integration updates are not implemented.

## Build and test

Requirements: Maven 3.9+, Java 17+, Node 22, Python 3.12+.

```sh
python3 -m pip install -r agent/requirements.txt
bash scripts/build.sh
npm test --prefix frontend
python3 scripts/local_smoke.py
```

`build.sh` compiles the UI into the JAR and runs the Java and Python suites. The local full-stack test runs the real backend and agent, with a simulated provider, in temporary directories. It never sends requests to Fly or Twilio.

## Operator references

Short references following the same separate costs/tokens format as Telugu Now:

- [Costs and billing dashboards](local-machine/costs.md)
- [Tokens, secret variable names, and signup invites](local-machine/tokens.md)
- [Accounts and resource identifiers](local-machine/accounts.md)
- [Common commands](local-machine/commands.md)
- [Non-secret settings and defaults](local-machine/settings.md)
- [Storage, retention, and recovery](local-machine/storage.md)
- [Small issues to track](local-machine/nitpick.md)

Detailed implementation and deployment guides:

- [Deployment and accounts](docs/DEPLOYMENT.md)
- [Configuration contract](docs/CONFIGURATION.md)
- [Cost algorithm and defaults](docs/ALGORITHM.md)
- [Deferred environment maintenance and manual image updates](docs/MAINTENANCE.md)
- [Verification and operating limits](docs/VALIDATION.md)

## Repository layout

```text
backend/      Spring Boot API, sessions, storage, Fly adapter, fixed lifecycle policy, migrations, tests
frontend/     React/Vite interface and xterm.js terminal
agent/        Python sandbox supervisor, sync, snapshots, PTY and proxy
images/       Blank and Java image recipes
scripts/      Builds, local/live smoke tests, credential setup and promotion
contracts/    Fly OpenAPI baseline
local-machine/ Costs, tokens, accounts, commands, settings, storage, and nitpicks
dist/         Runnable application JAR
```

Control-plane coordination and agent WebSockets currently require **one backend replica**. Scaling the backend horizontally needs shared leases and agent-routing ownership before increasing replica count. This constraint does not prevent multiple sandbox Machines.

## Guarded configuration and session logs

The configuration editor now uses one exclusive editing session across app and terminal. Terminal editing uses `irv edit`; direct writes to managed files are denied by the sandbox image. Logs have durable replay, explicit acknowledgements and separate stdout/stderr streams. See [docs/SYNC_AND_LOGS.md](docs/SYNC_AND_LOGS.md). Rebuild both sandbox images and backend for this protocol change.
