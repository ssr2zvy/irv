# Fixed lifecycle policy and deferred optimization

The active policy is `FixedLifecyclePolicy`, invoked by `SandboxService`. There is no active learned cost optimizer. `DeferredCostOptimizer.java` is entirely comments describing the deferred design. The old `Optimizer` and `CostModel` executable sources have been removed.

## Active behavior

1. Newly created sandbox Machines always attach a Fly working volume. The designated folder is `/persist/<folder_name>`, with the existing home-directory alias. The volume contains the working copy, not a saved RAM image.
2. Active terminal heartbeats and HTTP/WebSocket preview traffic renew usage leases. When the last lease expires, a fixed **300-second** idle countdown begins. A socket close can leave up to 45 seconds on its last lease; it does not override another preview's lease. Logs/config viewing does not renew the lease. Custom bounded background-job leases remain outside this patch.
3. The central scheduler polls every second. At or after the deadline, it begins a final checkpoint. Five minutes is the save trigger, not an unconditional destruction deadline. Worker scheduling and saving can take additional time.
4. The agent prevents new terminal creation, freezes user processes without killing them, syncs local config/scripts, uploads missing chunks, commits the manifest, reports save telemetry, and flushes its pending log queue. A final acknowledgement must name the backend's current committed snapshot and confirm log flushing.
5. A return before cleanup commits cancels deletion and unfreezes work. After verification and the recovery guard, the backend atomically records `deleting` with the verified snapshot ID. After this point a return finishes the committed cleanup and recreates the environment; it cannot undo a provider deletion already in flight.
6. Delete the Machine first, then its working volume. Keep IDs until each successful deletion; a provider 404 counts as already deleted. Retry partial cleanup after a failure or control-plane restart. Pin the committed snapshot during cleanup. Only after both resources are released mark the sandbox `cold`.
7. Reopening provisions a volume and Machine, restores the designated files from the selected incremental snapshot and applies current app-side configuration before startup. Base images remain registry content. Snapshot manifests remain in the app database; file chunks are in Tigris (or local blob storage in local tests).

An operation failure retains the remaining working resources and displays the error. Automatic retries wait 60 seconds. A backend restart during an uncommitted save unfreezes and retries from a working state; a restart during committed deletion resumes cleanup. Heartbeat/reopen activity clears the old absence and countdown. Prices and return history never change the five-minute duration.

Manual **Stop** remains save-then-stop with its volume retained; it deliberately does not destroy that volume. Reopen it to resume the automatic policy. Manual **Archive** uses final-save-and-release and then hides the sandbox record; it is not permanent snapshot erasure. Legacy stopped/suspended/volume records are retained until reopened; an old periodic snapshot alone does not authorize deletion. A legacy running Machine without a volume is checkpointed before cold cleanup; its next creation attaches a volume. There is no automatic live disk migration.

## Startup limit and retention exception

The startup clock is recorded from the reopen request, including time before its startup worker begins. The existing watchdog reports failure at 180 seconds and requests a provider stop. Required setup and agent readiness must finish before the terminal is reported ready. This is not a guarantee of success through outages or arbitrary hanging user scripts.

To preserve the earlier startup planning constraint, automatic cold cleanup is blocked if this deterministic conservative estimate exceeds 180 seconds:

- Initial estimate: `100 + workspace_bytes / (5 * 1048576)` seconds.
- Also consider `1.3 * duration + 5` for each of the last 30 recorded `cold` and `cold_failed` attempts; take the maximum.
- Check both before saving and after the final save updates actual workspace size.

Thus a workspace over approximately 400 MiB is retained under the initial estimate. A slow/failed cold start can also require retention. The UI shows the reason and remaining resources continue to incur charges. This is a fixed safety exception, not learned cost optimization. These estimates are empirical assumptions, not a mathematical latency bound: changed images, scripts and provider behavior require staging measurements. Investigate slow restore/setup rather than removing the guard to force deletion. This patch does not claim arbitrary-sized workspaces always restore within three minutes.

## Consistency, logs and limitations

Freezing provides a stable filesystem copy for ordinary sandbox-owned writers. It does not flush application buffers that only exist in RAM or guarantee application/database transaction consistency. Users must persist work inside the designated folder. The patch intentionally avoids killing processes before a save can succeed, so a failed save or pre-commit return can resume them. Do not run writers outside the supervisor's process-control scope.

Final agent logs must be acknowledged before automatic deletion. This does not promise that every last provider log line after the agent exits is captured; optional container-log polling remains separate. Agent images must be rebuilt: older agents lack the final `logs_flushed` acknowledgement and will safely block deletion.

## Measurements and displayed cost

The app continues collecting return intervals, save/restore/setup/readiness durations, failed startup durations, snapshots, existing provider metric imports, configured prices and lifecycle usage. It does not train predictors. `CostAccounting` owns price defaults and rate arithmetic only. Final resource-holding intervals are flushed before machine/volume deletion as well as on periodic ticks.

The displayed estimate remains a configured-rate machine/volume subtotal, not an invoice. It excludes Tigris object requests, object storage, transfers, fixed backend/database costs, taxes and invoice adjustments. Rate defaults are illustrative and must match your allocation/region/account. New volumes record their provisioned capacity; legacy volumes fall back to `IRV_VOLUME_GB`. Provider changes outside this application are reconciled periodically and can make metering approximate. Failed Machine states are conservatively charged as compute until reconciled.

## Deferred design

The comment-only Java file preserves the approved action/storage choices, learned return and recovery predictions, failure handling, billing conversion, data/vector structure, training/replanning triggers, uncertainty, observation bias, safeguards and evaluation requirements. None of that file executes. Historical decision rows remain available, clearly distinguished from the new `fixed-five-minute-v1` records. This explicit deferral supersedes the previous adaptive three-minute initial grace for the active implementation.
