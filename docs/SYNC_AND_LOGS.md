# Managed configuration and log delivery

This change implements the exclusive-editing decision made after the reconciled specification. It replaces routine concurrent editing/merging with one authorized editor for the complete configuration bundle. The deferred cost optimizer and environment updater remain deferred. The five-minute lifecycle and incremental Tigris workspace storage remain active.

## What is synchronized

`config.toml`, its single `entrypoint`, and every path explicitly listed in the top-level `helpers` array are managed together. All paths are relative to the designated workspace. Existing app-side helper files are registered during the first access after migration. Keyword definitions, optional processes, and declared preview ports remain part of this restricted TOML; no Fly infrastructure fields are exposed.

The backend stores committed bundles, hashes, revisions, pending delivery, and saved drafts. The machine keeps protected copies inside the designated folder, so snapshots include them. Arbitrary source files retain ordinary workspace/snapshot behavior. Image directory trees contain metadata only. Generated shell keywords remain runtime output.

Example:

```toml
schema_version = 1
folder_name = "workspace"
entrypoint = "scripts/environment.sh"
helpers = ["scripts/path.sh"]
preview_ports = [8080, 5173]

[keywords.work]
root = "workspace"
path = "."

[processes.backend]
command = "./mvnw spring-boot:run"
cwd = "backend"
# log_file = "backend/logs/application.log"
```

The setup script can source a helper with `source "$WORKSPACE_DIR/scripts/path.sh"`. Registering a helper alone does not execute it. Setup remains environment preparation, not a required Maven launch.

## Editing and ownership

Choose **Edit configuration** in the app, or run `irv edit config.toml` / `irv edit scripts/environment.sh` in the terminal. Terminal editing opens `$EDITOR` (default `nano`) on a writable draft. Valid saved disk changes are committed on the next one-second check; partially typed editor buffers are not visible until the editor saves. Create related helpers in the displayed draft directory and declare them in TOML. `irv status` prints the current configuration, lease owner surface, and draft metadata.

One database row serializes acquisition across backend instances. The lease lasts 120 seconds, renews every 20 seconds, and requires a private lease token plus a monotonically increasing fence. The token is returned only to the acquiring editor; status/draft lists do not disclose it. Saving, renewal, release, draft recovery, and takeover are checked by the server. Takeover explicitly revokes the prior lease; old drafts remain. A repeated successful commit with the same request ID and payload returns its original result, including after release. The old unguarded config endpoints reject writes.

The UI autosaves drafts after one second without typing; **Save changes** validates and commits the whole bundle. Invalid drafts remain recoverable but never replace the committed configuration. Saved browser drafts also survive a lost connection in local browser storage. Stale recovered drafts require explicit review against the current committed version. The terminal supports `irv edit --recover EDITOR_ID` and `--review-latest` after that review. Terminal disk drafts live under `$WORKSPACE_DIR/.irv-drafts/`, so ordinary workspace snapshots also protect them if central draft delivery fails. Unsaved terminal editor memory is subject to the editor's own recovery behavior.

Managed files are root-owned/read-only. Their containing directories are root-owned/sticky while remaining writable for ordinary user-created files. This prevents direct overwrite, chmod, unlink, rename, or parent replacement by the sandbox user. The agent/broker remains privileged; setup scripts, terminals and managed processes drop to UID/GID 1000, inherit no provider credentials, and run under the launcher's `no_new_privs`. The broker listens on a group-restricted Unix socket. Ordinary project files remain writable. No unrestricted root/sudo access is provided.

Unmanaging a helper preserves it as an ordinary writable file. Explicit **Delete file** also records a deletion, allowed only after removing its reference. CLI equivalent: remove its helper declaration and use `irv edit config.toml --delete scripts/helper.sh`. Changes to entrypoint require the replacement file to exist in the same bundle. Folder name remains fixed after first start. Existing 32-file/64-KiB-per-text limits remain.

## Delivery, startup and shutdown

The agent checks for committed revisions every second while running. It replays missed revisions in order, verifies managed contents, replaces files atomically, and acknowledges revision/hash. Failed acknowledgements are retried. UI status distinguishes app-saved/pending-machine, synced, draft validation errors, and synchronization failures. No user refresh is required for delivery.

Application briefly freezes development processes while replacing a multi-file revision. A durable intent record allows startup to finish an interrupted application. Retained machines resume from their applied revision; cold snapshots include the configuration revision so historical managed files are not replayed over later unmanaged user files. Older snapshots receive newer committed app config before setup. Existing differing machine copies are saved as recovery drafts before migration, not silently discarded.

Saving never runs setup. Keyword changes load at the next shell prompt. **Apply setup & restart processes** is explicit: it terminates user processes and sources the committed setup again. Save files first; this is not a VM recreation or a guarantee that arbitrary external side effects are reversible.

Editing in the app, inspecting logs, and holding an edit lease do not extend the compute lease. Terminal usage keeps the existing terminal heartbeat behavior. At fixed-policy shutdown, config delivery is reconciled before the snapshot and log flush. Saved drafts live in the app independently of that snapshot. New committed edits that arrive after the final checkpoint remain durable in the app and overlay the snapshot on next start.

## Logs are a separate one-way stream

No editing lease applies to logs. Each agent event carries its original session ID, occurrence timestamp in milliseconds, source, stream, producer ID, sequence, and stable event ID. Configured process stdout and stderr are separate; declared file logs use the `file` stream. The raw terminal transcript is not automatically retained.

The agent appends events to a durable, fsynced replay queue before upload. The backend validates and commits a whole batch transactionally, deduplicates by sandbox/event ID, and explicitly acknowledges its IDs. Missing acknowledgements leave the batch queued; a lost HTTP response safely replays it. Events keep their original session and occurrence time across delayed delivery. Queue access is serialized with final flushing. Agent restarts on a retained volume reload the queue. Volume loss before delivery can still lose unacknowledged records; a verified final log flush is required before planned deletion.

The queue is at `/persist/.irv-runtime/private/events.jsonl`, outside the user snapshot, and is never truncated to meet a retention cap. A readable local archive is at `/persist/.irv-runtime/logs/events.jsonl`, with four rotated 8-MiB segments. Use `irv logs --local` for the current local segment; `irv logs`, `irv logs --session ID`, and `irv logs --follow` read app history, which includes platform/provider events. Failed API delivery does not erase the local queue/archive. The UI shows queue depth, last backend acknowledgement, sources/streams and refreshes the session selector.

Provider logs remain externally collected, so a missing agent does not remove the diagnostic path. Machine-create/API errors and Machine events are recorded by the backend. Container stdout is polled through `fly logs --json --no-tail` every ten seconds, including a short post-deletion tail; parseable provider timestamps select the originating session. Collector failures are surfaced as `log-sync` records. This provider path is best-effort: upstream retention and polling gaps cannot be eliminated by the agent outbox protocol. The app's default log retention remains 30 days (`IRV_LOG_DAYS`). Local archives expire with the working volume; historical app logs remain.

## Operator compatibility

Rebuild and deploy backend plus both sandbox images together. Old agents cannot use the new lease protocol, and the backend rejects their unguarded writes. Never run old and new image code concurrently against the same live working volume. After a verified stop, the configuration page offers **Recreate stopped Machine for image upgrade**. It removes only the stopped Machine, retains its volume, and selects the administrator-configured image for the next start. See the patch's `README_APPLY.md` for checkpoint/migration sequence and deployment verification.

No new secrets are introduced. The existing per-sandbox agent token remains private to the broker; `FLY_API_TOKEN` remains backend-only. Protocol tests can use the explicitly named local smoke fixture, which simulates UID switching and Unix socket transport on restricted hosts. That fixture is not shipped in either sandbox image.
