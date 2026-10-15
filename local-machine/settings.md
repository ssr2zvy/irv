# Settings

Non-secret settings consumed by the included code. Secrets and the initial administrator invite are in [tokens.md](tokens.md). Set these in the backend environment (Fly configuration/secrets in production; root `.env` locally), unless another consumer is named. Changing local files does not change a deployed environment.

## Backend and services

| Name | Code default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | Backend HTTP port; keep Fly's internal port consistent. |
| `IRV_PUBLIC_ORIGIN` | `http://localhost:8080` | Public backend URL used for origin checks and agent connections. Use HTTPS in production. |
| `IRV_PREVIEW_ORIGIN` | `http://localhost:8080` | Base origin for token subdomains. Configure a separate wildcard domain in production. The local default does not provision wildcard DNS. |
| `IRV_SECURE_COOKIES` | `true` | Require HTTPS cookies. `.env.example` overrides to `false` for local HTTP; change to `true` in production. |
| `DATABASE_URL` | Local H2 under `./data/irv` | Production JDBC PostgreSQL URL, e.g. `jdbc:postgresql://HOST:5432/irv?sslmode=require`. Compose supplies its internal URL. |
| `DATABASE_USER` | `sa` | Database user. `.env.example` and Compose use `irv`. |
| `FLY_ORG` | Empty | Organization slug for sandbox Apps and provider metrics. Required for cloud sandboxes. |
| `FLY_REGION` | `iad` | Sandbox Machine/volume region. Control-plane region is separately in `fly.toml`. |
| `IRV_BLANK_IMAGE` | Empty | Pullable blank sandbox image; use an immutable digest. |
| `IRV_JAVA_IMAGE` | Empty | Pullable Java/frontend image; use an immutable digest. |
| `S3_BUCKET` | Empty | Workspace chunk bucket. Empty selects local blob storage; production should set Tigris. |
| `S3_ENDPOINT` | `https://fly.storage.tigris.dev` | S3-compatible endpoint. |
| `AWS_REGION` | `auto` | Region used by the Tigris SDK client. |
| `IRV_BLOB_DIR` | `./data/blobs` | Local fallback blob directory when no bucket is set. Compose uses `/data/blobs`. |

Image catalog promotion can override initial image references in the database; see [maintenance](../docs/MAINTENANCE.md).

## Capacity, retention, and lifecycle

| Name | Default | Meaning |
| --- | --- | --- |
| `IRV_MACHINE_MEMORY_MB` | `4096` | Sandbox RAM; Fly adapter uses two shared CPUs. |
| `IRV_WORKING_STORAGE` | no longer read | New Machines always attach a volume. Existing Machines are not migrated live. |
| `IRV_VOLUME_GB` | `10` | Capacity when creating a working volume; does not resize existing volumes. |
| `IRV_MAX_SANDBOXES_PER_USER` | `5` | Maximum unarchived sandbox records per user. |
| `IRV_STORAGE_LIMIT_GB` | `20` | Per-sandbox stored chunk limit, including chunks retained by backups. |
| `IRV_BACKUP_DAYS` | `7` | Old snapshot retention; the currently selected snapshot is preserved. |
| `IRV_LOG_DAYS` | `30` | Retention for logs stored by the app. |
| `IRV_SUSPEND_ENABLED` | no longer read | Adaptive state selection is deferred; the fixed policy never automatically suspends. |
| `IRV_FLY_LOGS` | `false` in Java | Enables Fly stdout import; the backend Docker image sets `true`. Direct JAR runs need flyctl installed. |
| `FLYCTL_PATH` | `flyctl` | Executable used for provider log import. |
| `FLY_API_URL` | `https://api.machines.dev/v1` | Provider endpoint. Override only for controlled integration testing. |

The 180-second startup deadline and fixed 300-second idle period are separate code policies. Idle timing does not adapt. A conservative cold-recovery guard can retain resources; see the documented 400 MiB initial estimate and measured-duration exception. Pricing inputs are stored through Administration, not environment variables. See [algorithm](../docs/ALGORITHM.md).

## Manual staging validation

Set `IRV_SMOKE_ORIGIN` to the staging backend's HTTPS origin in the operator shell running `scripts/live_smoke.py`. Its login credentials are listed in [tokens.md](tokens.md). No GitHub Actions settings are required by this repository after removal of the update and deployment workflows.

Credentials are listed in [tokens.md](tokens.md). Agent-internal environment variables are set by irv; users configure their own environment through the restricted [workspace TOML](../docs/CONFIGURATION.md).
