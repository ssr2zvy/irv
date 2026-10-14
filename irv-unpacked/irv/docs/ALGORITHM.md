# Lifecycle controller

## Inputs and learned quantities

The controller uses machine state, retained volume, elapsed absence, workspace bytes, current configured rates, save/restore observations, and the owner's past return intervals. CPU metrics are imported for operational visibility; Fly compute billing uses provisioned resources and lifecycle duration, not raw CPU utilization.

`CostModel` is a small Bayesian piecewise-constant hazard model. Return-time intervals are 0, 1, 3, 10, 30 minutes, 1, 4, 12 hours, 1, 3, 7, 30 days and an unbounded tail. Each interval estimates events/exposure with a weak six-hour prior. A still-open absence contributes exposure without an invented return. The last 500 owner observations are considered; weights decay with a 30-day half-life and other workspaces have one-quarter weight. At present time-of-day/weekday features are not used. They can be added when enough history exists to estimate them reliably.

Recovery bounds use the largest of a conservative initial size-based estimate and 1.3 times each of the last 30 measured startup times plus 5 seconds. This is a conservative empirical guard, not a mathematical upper bound on provider failures. A changed image or arbitrary user script can invalidate historical performance.

## Decisions

Candidate states: running, suspended, stopped, destroyed with retained volume, and fully cold. A volume-retention candidate is available only when a volume exists. The deployment setting `IRV_WORKING_STORAGE=ephemeral|volume` controls the working-copy medium at creation; this release optimizes retention/deletion of that medium, not live migration between root disk and a newly provisioned volume.

The planner compares expected holding charges, save/transition charges, and return-weighted recovery charges. It uses dynamic programming over a seven-day grid with 30-second intervals initially, five-minute intervals in the medium term, and hourly intervals later. A hazard-based tail value includes continued holding/recovery cost beyond the horizon. It executes only the first action and replans after observations or at its scheduled next decision.

Transitions normally move toward cheaper retained states. If a retained state's measured recovery bound becomes greater than 180 seconds, the controller prewarms it. No fixed running → suspended → stopped → destroyed staircase is imposed. Predicted savings must exceed $0.00001 after transition costs to trigger an immediate state change.

With fewer than five completed owner observations, the initial grace is 180 seconds after disconnect. This is unrelated to the separate 180-second startup deadline. After enough observations, the learned plan controls the grace period. The new user prior is explicit; this version does not perform paid randomized exploration.

## Startup constraint

The optimizer rejects recovery options estimated to exceed 180 seconds. An opening request starts a recorded deadline. The terminal is ready only after the entrypoint environment setup and agent initialization succeed. The backend marks startup failed at the deadline and requests a provider stop. It retains data and exposes logs rather than waiting indefinitely. An independent deadline watchdog runs every second; the UI countdown reaches zero at 180 seconds. This is a deadline/error policy and planning constraint, not a guarantee that cloud outages or user scripts can be overcome in three minutes.

## Persistence and concurrency

Terminal heartbeats and recent preview requests hold a 45-second usage lease. Logs/config UI activity does not. On expiry, one absence is recorded and decisions begin. Reopening records a return even if the Machine was already destroyed. Final save precedes destructive transitions. Regular five-minute checkpoints freeze user processes briefly and resume them afterward. Final stop/destroy first terminates user processes (10-second graceful shutdown, then kill), snapshots, verifies the commit, and only then deletes resources. Suspension preserves processes, frozen during the final checkpoint until resume. The controller decides when the transition begins; the ten-second process termination grace is a flush safeguard, not an independent idle timeout.

A save failure cancels destructive cleanup and records an error. If a reconnect arrives during saving, its lease prevents the pending destruction. Startup, saving and destruction are serialized per sandbox. Locks and WebSocket ownership are in process, therefore run one control-plane replica.

## Cost reporting and limitations

Seeded rates are illustrative and must be replaced with actual organization/region prices in Administration. The model uses measured bytes and timing and does not assert provider host cache hits. Running/retained-state usage is sampled every minute; the UI subtotal is explicitly an estimate and excludes object requests/transfer. Exact invoice ingestion is not implemented. Maintain invoice reconciliation externally. No absolute cheapest-cost guarantee is claimed.

Backup retention is seven days, logs thirty days, snapshots every five minutes. These are initial operating defaults, not learned parameters. Full manifests are stored as database metadata, while content chunks are in Tigris. Snapshot storage is never a full root filesystem snapshot. Unreferenced chunks have a 24-hour collection grace, and the latest selected snapshot is retained even if older than the backup window.
