# Storage

## What lives where

| Data | Storage | What survives Machine destruction |
| --- | --- | --- |
| Base operating system, agent, Java/Node tools | Versioned container images in the registry | Recreated from the selected image; user changes outside the workspace are not backed up. |
| Designated workspace | `/home/dev/<folder_name>`; volume mode uses `/persist/<folder_name>` with a home symlink | Files from the last successfully committed snapshot can be restored from Tigris. |
| `config.toml`, entrypoint, helper scripts | Inside the designated workspace and revisioned text in the app database | Current app-side versions overlay the selected workspace snapshot on startup. |
| Workspace content chunks | Tigris; local fallback under `IRV_BLOB_DIR` | Remain until unreferenced and collected. No full VM filesystem is uploaded. |
| Snapshot manifests and selected snapshot | App database | Required to reconstruct the directory tree from chunks. |
| Accounts, invite hashes, MFA records, sandbox metadata, history | App database | Persist independently of sandbox Machines. Encrypted factors also require the original encryption key. |
| Accepted session/platform/process logs | App database | Available for `IRV_LOG_DAYS` (default 30). |
| Unacknowledged agent log events | `/tmp/irv-runtime/events.jsonl` in the Machine | Not durable after Machine loss; events already accepted by the app remain. |
| Saved directory picker tree | App database | Metadata only; no base-image file contents. |

## Incremental backups

Files are split into 4 MiB SHA-256 chunks, deduplicated within each sandbox. Each snapshot stores a complete logical manifest referring to chunks; restoring does not replay a chain of diffs. The image's shared layers are handled separately by the container registry/runtime.

Only the designated workspace is covered. File contents, directories, symlinks, modes, and modification times are preserved; ACLs, extended attributes, hard-link identity, sparse extents, devices, and sockets are not. Periodic snapshots freeze processes for filesystem consistency. Final saves freeze work, verify the snapshot and flush pending agent logs before releasing resources; application-specific database consistency still requires application-level persistence. A failed final save unfreezes work.

Daily cleanup removes snapshots older than `IRV_BACKUP_DAYS` (default 7), except the currently selected snapshot. Unreferenced chunks older than one day are eligible for collection. Retained backups can therefore occupy storage even after files are removed from the live workspace.

## Lifecycle and deletion

The active policy keeps a working volume while running, waits five idle minutes, then saves and releases the Machine and volume. Save/log failures, returns before deletion commits, and the conservative recovery guard block automatic deletion. Manual Stop retains its volume. Legacy retained states require reopening; there is no live working-disk migration. Adaptive optimization is deferred.

Archiving a sandbox removes it from the active list/quota but retains its metadata and selected snapshot. It is not a permanent data-erasure operation. Deleting a Fly resource manually does not remove Tigris chunks or database records, and can lose unsaved work.

## Recovery

Back up the app database and protect `IRV_ENCRYPTION_KEY` in addition to retaining object storage. Chunks without their database manifests are not sufficient for normal irv restore. Keep database/object-store recovery points coordinated so restored manifests still reference available objects.

The local JAR stores H2 metadata under `./data`; Compose uses named PostgreSQL and blob volumes. `docker compose down -v` deletes those local volumes. Production should use PostgreSQL and Tigris, not unmounted local container storage.

Historical snapshot selection is currently an API operation; see [commands.md](commands.md). The detailed workspace/sync behavior is in [configuration](../docs/CONFIGURATION.md).
