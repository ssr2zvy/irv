# Commands

Run from the extracted `irv/` root. Replace uppercase resource placeholders. Credentials belong in the stores documented in [tokens.md](tokens.md).

## Generate local configuration and start

```sh
python3 scripts/init_env.py
# Edit .env with your configuration; the script refuses to overwrite an existing file.
set -a
. ./.env
set +a
java -jar dist/irv.jar
```

Alternatively, after generating `.env`:

```sh
docker compose up --build
```

Open `http://localhost:8080`. Use the bootstrap invite from `.env` for initial signup, then enroll MFA. Cloud sandbox creation needs Fly credentials and published images. A cloud agent cannot reach a backend bound only to your laptop's localhost; use a publicly reachable HTTPS backend for real cloud sandbox tests.

## Build and verify locally

```sh
python3 -m pip install -r agent/requirements.txt
bash scripts/build.sh
npm test --prefix frontend
python3 scripts/local_smoke.py
```

Requires Java 17+, Maven 3.9+, Node 22, and Python 3.12+. The smoke test uses a simulated Fly API and real local backend/agent, with no cloud spending. See [validation](../docs/VALIDATION.md) for coverage and limitations.

## Deploy and inspect the backend

After following [deployment setup](../docs/DEPLOYMENT.md), setting the app name in `fly.toml`, and editing `.env` for production:

```sh
fly secrets import --app YOUR_IRV_APP < .env
fly deploy --ha=false --app YOUR_IRV_APP
fly status --app YOUR_IRV_APP
fly logs --app YOUR_IRV_APP
```

Import only the environment for the intended staging/production app. Use your authenticated Fly operator/deployment identity. Remove the consumed bootstrap secret after admin signup:

```sh
fly secrets unset IRV_BOOTSTRAP_INVITE --app YOUR_IRV_APP
```

Also remove it from your local `.env` and unset it in any current shell. Unsetting the variable does not itself revoke an unused invite already stored in the database.

## Invite another user

Sign in as the administrator and use Administration to generate an invite. For API use, the following can be run in your own browser's developer console while signed in to irv with completed MFA:

```js
await fetch('/api/admin/invites', {
  method: 'POST',
  headers: {'Content-Type': 'application/json', 'X-Irv-Request': '1'},
  body: JSON.stringify({hours: 24})
}).then(r => r.json())
```

The response contains a sensitive single-use signup code. Share it privately. Listing and revocation routes are documented in [tokens.md](tokens.md).

## Inspect and select a workspace backup

From an authenticated owner session, `GET /api/sandboxes/{id}/snapshots` lists retained backups. Select one using `POST /api/sandboxes/{id}/restore/{snapshot}` with `X-Irv-Request: 1`. Selection requires a fully cold sandbox with no Machine or volume; a merely stopped sandbox does not qualify. The next open restores it, then overlays current app-side config/scripts. Do not delete resources manually to bypass the verified-save lifecycle. See [storage.md](storage.md).

## Live staging checks and image publication

With the four `IRV_SMOKE_*` values set in a protected shell environment:

```sh
python3 scripts/live_smoke.py
```

This creates billable canaries. If interrupted, inspect staging resources for cleanup. Image build/push commands are in [deployment](../docs/DEPLOYMENT.md); manual image selection and rollback are in [maintenance](../docs/MAINTENANCE.md). These commands do not run on a schedule; the environment-maintenance worker is deferred.

## Managed editing and logs

- `irv edit config.toml` — acquire the bundle lease and edit with `$EDITOR` (default nano).
- `irv edit scripts/environment.sh` — edit the entrypoint through the same lease.
- `irv edit --takeover config.toml` — explicitly revoke an existing editor.
- `irv edit --recover EDITOR_ID config.toml` — recover a saved draft; use `--review-latest` only after review.
- `irv status` — current committed bundle, pending acknowledgement and editor status.
- `irv logs --follow` / `irv logs --session ID` — app-side log history.
- `irv logs --local` — current local log archive segment.
