# Workspace contract

New Machines keep the designated directory at `/persist/<folder_name>` on a working volume, exposed through `/home/dev/<folder_name>` as a symlink. Legacy ephemeral Machines are retained until a verified save and cold recreation. Only that directory is backed up. The folder name is a single validated component and becomes immutable after the first start; users cannot choose a host mount or an arbitrary persistence path.

`config.toml`, the entrypoint and helpers live inside this directory and are also maintained as revisioned text in the application database.

```toml
schema_version = 1
folder_name = "workspace"
entrypoint = "scripts/environment.sh"
preview_ports = [8080, 5173]

[keywords.backend]
root = "workspace"
path = "backend"

[keywords.tools]
root = "image"
path = "/opt"

[processes.backend]
command = "./mvnw spring-boot:run"
cwd = "backend"
# Optional extra file to stream as a separate source:
# log_file = "backend/logs/application.log"

[processes.frontend]
command = "npm run dev -- --host 0.0.0.0"
cwd = "frontend"
```

The entrypoint is an environment setup script. Maven is only an optional named process, not an automatically inserted startup command.

```bash
#!/usr/bin/env bash
export PATH="$WORKSPACE_DIR/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
# source "$WORKSPACE_DIR/scripts/helper.sh"
```

The agent sources the entrypoint once, captures its exported variables, then uses them for terminals and managed processes. Setup must finish within 90 seconds and the total opening request must be ready within 180 seconds. Long-running applications belong in `processes`, not a foreground entrypoint. An immediate process exit is logged; terminal readiness does not promise that an arbitrary application on a preview port has finished booting.

Keyword functions are generated inside the image runtime and sourced at each shell prompt. They change the current shell directory. Changes appear at the next prompt. The UI picker uses the most recently saved directory tree, never the contents of image directories. Stale paths produce normal shell errors.

Editing config/scripts:

- UI writes use the displayed revision; stale revisions return HTTP 409.
- Agent local changes are checked every second when connected.
- If UI changes race unsynced terminal edits, the agent preserves the remote bundle in `/tmp/irv-runtime/remote-config-conflict.json` and logs a conflict. Resolve the edit explicitly; neither side silently overwrites the other.
- Offline app edits overlay the restored workspace at startup.
- Changed entrypoint/process commands take effect on restart; saving does not repeatedly execute scripts while you type.
- Startup helper files under `scripts/` are discovered automatically, with a maximum of 32 files of 64 KiB each.

Logs are emitted to the machine's stdout and buffered locally in `/tmp/irv-runtime/events.jsonl` until accepted by the app. The buffer is a transport spool, not an infinite local log archive. Historical logs live in the app for 30 days. Providers' machine event records are collected separately so creation failures and early exits can be inspected even when the agent never becomes ready. The Docker backend also imports recent Fly container stdout with `fly logs --json --no-tail` every 30 seconds, including early exits. Set `IRV_FLY_LOGS=true` and install flyctl when running the JAR directly. Provider log retention and polling gaps can still cause missing host logs; direct Fly logs remain available for diagnosis.

Snapshots use 4 MiB SHA-256 chunks, with deduplication within each sandbox. Every snapshot is a complete logical file tree pointing to reusable chunks. Restoring does not replay a chain of filesystem diffs. Restore preserves regular files, directories, symlinks, permissions, and mtimes. It does not preserve extended attributes, ACLs, sparse extents, hard-link identity, sockets, device nodes, or files outside the designated directory. Database applications should shut down cleanly before final save; frozen periodic checkpoints provide filesystem consistency, not application-specific transaction guarantees.
