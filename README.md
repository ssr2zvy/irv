# irv

Invite-only browser development environments, with a Spring Boot control plane, React interface, Fly Machines, and incremental workspace storage in Tigris.

**This repository contains implementation source and a runnable application JAR.** Local verification is recorded in `docs/VALIDATION.md`. Live Fly/Tigris/SMS deployment and Docker image builds require your accounts and must pass the included staging tests before production use. No credentials are bundled.

## Quick start: run the app locally

Requirements: Java 17+ and Python 3 for generating secrets. Docker Compose is an alternative.

```sh
python3 scripts/init_env.py
# Edit .env: set Fly organization/token and image references when ready to run cloud sandboxes.
set -a
. ./.env
set +a
java -jar dist/irv.jar
```

Open **http://localhost:8080**. Register a username/password using the `IRV_BOOTSTRAP_INVITE` value in `.env`, then scan the enrollment QR with Microsoft Authenticator or Duo Mobile. Save the recovery codes. No email address is collected. The first invite creates the administrator and expires 24 hours after the first application boot. Remove the bootstrap variable after setup.

The local JAR uses an H2 database under `./data`, and filesystem blob storage if `S3_BUCKET` is empty. This exercises the real app without a database subscription. Sandbox creation still uses Fly; configure the image references and Fly credentials first. To verify without spending on Fly, build from source and run `scripts/local_smoke.py`, which substitutes only the provider API.

Alternatively:

```sh
docker compose up --build
```

Compose runs PostgreSQL plus the application. Credentials are read from `.env`. Persist the named volumes. The Compose setup does not provision cloud sandboxes until you request one.

## What is included

- Username/password authentication, expiring single-use signup invitations, mandatory TOTP or optional Twilio Verify SMS, one-use recovery codes, encrypted factor secrets, rate limits, session revocation, CSRF origin/header checks.
- A sandbox dashboard with browser PTY terminal, isolated-origin temporary previews, environment/process logs by session, startup and idle countdowns, configuration/script editing, directory shortcuts, and inspectable cost decisions.
- Restricted `config.toml`, one sourced environment entrypoint, helper scripts, named processes, optional file logs, shell directory functions. Infrastructure configuration stays in the backend.
- Two image recipes: a common blank Python/Bash/Git/agent image, and an extended JDK 17/Maven/Node 22 image. The common layers are reused through image inheritance.
- One Fly App and distinct private network per sandbox. Only an environment-specific bearer token is passed to the agent; Fly/Tigris/maintenance credentials remain in the control plane.
- Incremental fixed-size chunk snapshots of only the designated workspace. Hash verification, restore verification, file modes, timestamps, symlinks, empty directories, seven-day backup retention, and orphan-chunk collection.
- Revision-controlled two-way TOML/script sync with conflict preservation. Configuration can be edited while an environment is offline.
- A learned return hazard model, conservative measured recovery bounds, and a planner choosing lifecycle actions and delays under the 180-second startup constraint.
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
backend/      Spring Boot API, sessions, storage, Fly adapter, planner, migrations, tests
frontend/     React/Vite interface and xterm.js terminal
agent/        Python sandbox supervisor, sync, snapshots, PTY and proxy
images/       Blank and Java image recipes
scripts/      Builds, local/live smoke tests, credential setup and promotion
contracts/    Fly OpenAPI baseline
local-machine/ Costs, tokens, accounts, commands, settings, storage, and nitpicks
dist/         Runnable application JAR
```

Control-plane coordination and agent WebSockets currently require **one backend replica**. Scaling the backend horizontally needs shared leases and agent-routing ownership before increasing replica count. This constraint does not prevent multiple sandbox Machines.
