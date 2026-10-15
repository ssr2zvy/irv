// package dev.irv;
//
// DEFERRED DESIGN ONLY -- NO EXECUTABLE IMPLEMENTATION, NO SPRING BEAN.
// This entire source file is comments. The active policy is FixedLifecyclePolicy.
// Do not describe this file, a scheduled workflow, telemetry collection, or a fixed
// shutdown timer as an implemented adaptive optimizer.
//
// public final class DeferredCostOptimizer {
//
// Goal and decision space
// -----------------------
// Minimize predicted total infrastructure dollars over a common comparison horizon,
// including residual future expense, subject to persistence and a conservative
// request-to-usable-terminal recovery estimate <= 180 seconds. This cannot guarantee
// hindsight-optimal expense or successful recovery through provider outages.
// Choose BOTH the next action AND its delay. Reevaluate after executing the first
// action; an absence may involve several actions, with no mandatory staircase.
// Machine state: running, suspended, stopped, destroyed.
// Storage: temporary working disk, retained Fly volume, Tigris-only saved workspace.
// Only evaluate feasible combinations and supported migrations; creating/mounting a
// volume or moving data incurs work and cost. Destroying a machine while retaining its
// volume differs from deleting both. Suspend's RAM/CPU snapshot is managed by Fly,
// separately from the workspace volume, and requires a cold-start fallback.
// Timing of process termination belongs to this controller, subject to active leases.
//
// Persistence scope
// -----------------
// Retain only the designated workspace, config.toml, entrypoint and helper scripts
// durably. Tigris contains content-addressed chunks; each committed manifest describes
// the complete saved tree, including deletions, modes and symlinks. No diff replay is
// needed. Immutable base-image layers remain in the registry/runtime, not in the
// workspace backup. A cached image landing on a particular host is not guaranteed.
// Preserve the latest snapshot and configured backups; reclaim only unreferenced
// chunks. Account for genuinely reclaimable bytes, not all logically deleted bytes.
//
// Observations to collect and retain
// ---------------------------------
// Backend: sandbox/user IDs, current machine/storage state, actual configured CPU/RAM,
// image digest, config revision, region, usage leases, last active use, reopen-request
// time, lifecycle transitions, requested/started/completed timestamps and failure type.
// Provider/agent: CPU, memory, network, disk measurements and their age/quality; workspace
// size, changed bytes, missing chunks, upload/download bytes, requests by operation,
// allocated volume capacity, retained rootfs size, stored unique chunks and manifests.
// Measure save, stop, suspend, resume, recreate, restore, setup and end-to-end readiness.
// Include failed resumes, fallback cold starts, cancellations, timeouts and retry costs.
// Meter resource holding intervals and estimated expense; reconcile with attributable
// provider invoices when available. Do not assume an exact live per-sandbox billing API.
// Record failures as failures, never successful low-resource/zero-cost outcomes.
// Continue tracking absence after resources are destroyed. Count an ongoing absence as
// censored exposure once, not a new independent failed return at every scheduler tick.
//
// Typed data and deterministic feature encoding
// --------------------------------------------
// Correlate observations by sandbox ID, machine instance, decision ID and attempt ID.
// Store metric name, value, unit, observed_at, received_at, source and quality.
// Persist the pre-decision feature snapshot, action, delay, model/schema version and
// predictions before execution. Join later outcomes by attempt ID, avoiding future-data
// leakage. Keep missingness/measurement age; never encode missing telemetry as zero.
// FeatureBuilder produces an ordered, versioned numeric vector: scaled/log sizes and
// durations, categorical indicators for image/state/action, cyclic calendar features,
// elapsed absence, current allocations, volume presence and recent summary measurements.
// IDs are grouping keys/categories, not ordinal magnitudes. Avoid counting hundreds of
// telemetry samples from one attempt as hundreds of independent recovery attempts.
//
// Predictors and training -- proposed mathematics, not a finalized solver
// ----------------------------------------------------------------------
// 1. Survival/return predictor with elapsed absence, user/sandbox history, time of day
//    and weekday. A piecewise exponential hazard with regularized contextual effects
//    is a candidate. Borrow shared history when individual data is sparse; favor recent
//    observations. Use censored likelihood for incomplete absences.
// 2. Small regularized/Bayesian regressions for uncertain phase durations and resource
//    quantities, conditioned on action, image, workspace/changed bytes and configuration.
//    Positive targets may use log transforms. Quantities already known need no model.
// 3. A failure/fallback predictor where sufficient observations exist, with conservative
//    priors otherwise. A timeout is not a successful duration of exactly 180 seconds.
// Fit using valid observed outcomes and suitable likelihood losses, not a sum of raw
// seconds, bytes, percentages and dollars. Evaluate the stored original prediction
// against its outcome before updating; cheaper outcomes also update the model.
// The prior hazard/DP implementation was partial and has been removed from execution.
// Exact model family, priors, retraining batch size and exploration limits remain open.
//
// Price calculator and constrained controller
// ------------------------------------------
// Cost = billable quantities x applicable unit prices, using each provider's billing
// granularity, allocation, free allowances and transfer direction. CPU busy percentage
// is not billed compute. Include save/restore/restart running time, retained root disks,
// allocated volumes, incremental object storage, request classes, billable transfer,
// retries/fallbacks and maintenance caused by the choice. Avoid double counting.
// Identical fixed control-plane costs belong in total reporting but do not change the
// winning lifecycle action. Prices are inputs, not coefficients learned from CPU usage.
// Use return probability to compare expected retention, transition and future recovery
// expense. Account for returns during transitions and the expense beyond the horizon.
// Dynamic programming over candidate times is one possible planner, not a requirement
// for a neural network, an LLM, direct-dollar regression or reinforcement learning.
// Reject infeasible/recovery-too-slow options before cost minimization. Keep data safety
// and active-use checks in independent execution code; a cheap invalid outcome cannot
// win merely because a penalty was too small. Add switching costs, dwell behavior and
// an uncertainty margin to prevent oscillation. Never silently reduce CPU/RAM or invent
// arbitrary commands: only approved lifecycle operations are available to the planner.
//
// Triggers and execution
// ----------------------
// Collect observations as backend/agent/provider events arrive. A learning worker fits
// when usable return/transition outcomes or censored exposure updates are available;
// coalesce close events and deduplicate attempt IDs. Not every CPU sample needs training.
// Replan on disconnect/lease expiry, return, planned action time, save success/failure,
// material price/workspace/image/performance changes and updated learned predictions.
// Replanning can use the same fitted model with new inputs; it need not await retraining.
// Publish versioned plans; check the version/current lease before execution. A returning
// user restores access immediately without waiting for model fitting. Serialize resource
// mutations, persist their commit/retry state, and cancel pending cleanup before its
// irreversible commit point. After that point, finish cleanup then recreate safely.
// Retain sufficient state when recovery cannot meet the 180-second planning constraint.
// Expose provider/startup failure by the deadline; a timeout is not proof of readiness.
//
// Cold start, validation and visibility
// ------------------------------------
// The original adaptive proposal started with ~3 minutes idle grace, then learned action
// and timing. That proposal is deferred; ACTIVE code now uses FIVE minutes fixed idle.
// Initial forecasts for untried actions require measured benchmarks or conservative
// priors. Only chosen-action outcomes are observed; counterfactual costs remain estimates.
// No paid/random exploration is authorized by this comment. Establish explicit bounds
// before introducing it. Compare against fixed policies using measured history and mark
// unobserved alternatives as estimates. Better prediction loss alone does not guarantee
// a smaller bill. Keep decisions, alternatives, predicted costs/recovery, actual outcomes,
// failures and reasons inspectable in the UI. Do not label savings as measured when they
// came only from a counterfactual estimate.
// }
