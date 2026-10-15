# Workspace contract

New Machines keep the designated directory at `/persist/<folder_name>` on a working volume, exposed through `/home/dev/<folder_name>` as a symlink. Legacy ephemeral Machines are retained until a verified save and cold recreation. Only that directory is backed up. The folder name is a single validated component and becomes immutable after the first start; users cannot choose a host mount or an arbitrary persistence path.

`config.toml`, the entrypoint and helpers live inside this directory and are also maintained as revisioned text in the application database.

```toml
schema_version = 1
folder_name = "workspace"
entrypoint = "scripts/environment.sh"
helpers = []
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

Editing config/scripts now uses exclusive edit sessions. Use **Edit configuration** in the app or `irv edit` in the sandbox. Managed files are protected against direct edits; the CLI edits a draft and publishes through the same lease as the web editor. Valid saves synchronize without rerunning setup. Invalid drafts remain recoverable. App and machine acknowledgement revisions are shown separately. See [SYNC_AND_LOGS.md](SYNC_AND_LOGS.md) for the complete protocol, takeover, helpers, deletion and recovery behavior.

Logs retain original timestamps/session/stream and use explicit batch acknowledgements, durable retry and deduplication. Local archives and app history are separate from the delivery queue. Stdout and stderr remain distinct. Provider diagnostics are collected outside the agent. See [SYNC_AND_LOGS.md](SYNC_AND_LOGS.md) for paths, retention and the provider polling limitation.

Snapshots use 4 MiB SHA-256 chunks, with deduplication within each sandbox. Every snapshot is a complete logical file tree pointing to reusable chunks. Restoring does not replay a chain of filesystem diffs. Restore preserves regular files, directories, symlinks, permissions, and mtimes. It does not preserve extended attributes, ACLs, sparse extents, hard-link identity, sockets, device nodes, or files outside the designated directory. Database applications should shut down cleanly before final save; frozen periodic checkpoints provide filesystem consistency, not application-specific transaction guarantees.
