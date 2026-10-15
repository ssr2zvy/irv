# Fixed-policy patch validation

Validated locally on 2026-10-15 UTC against source from upstream commit `ae74fbbf4a267b0fc7fe21fccf3adfdd7ca499a7`.

## Executed successfully for this patch

- Compiled all production Java source and `FixedPolicyChecks` with Java 17.0.20 against the 90 runtime dependency JARs extracted from the repository's existing application JAR (including Spring Framework 6.2.19).
- **20 Java lifecycle scenarios**, executed through `FixedPolicyChecks.main`: absolute five-minute scheduling, lease-expiry timing, save/delete ordering, active-use protection, heartbeat cancellation, save failure/retry backoff, snapshot and log-flush acknowledgement guards, return during save, an actual concurrent reopen, post-save workspace growth, failed cold-start retention, partial-deletion recovery after backend restart, legacy stopped-state protection, interrupted-save recovery, mandatory volume creation, request-time startup accounting, idempotent provider deletion, final usage accounting, expired queued startup rejection and pre-queue deadline publication. The JUnit `LifecycleTest` invokes the same checks during a normal Maven build.
- **8 Python tests**: existing path/config/incremental-snapshot/restore protections plus final config synchronization, final log acknowledgement, log-flush failure retention, and concurrent log uploader safety.
- **2 frontend helper tests**, plus a Vite 7.3.7 production build using the already installed matching dependency tree. Vite reports existing TOML eval/externalized-stream and large-bundle warnings.
- **Real local backend + real Python agent**, using a simulated Fly API and local blob storage: application boot and migrations, invite signup/TOTP, PTY and file writing, incremental save, two-way config synchronization, HTTP and WebSocket previews, logs, final checkpoint/log flush, Machine-then-volume deletion, new-volume creation and cold restore of the saved file. This used a temporary locally compiled executable JAR assembled with the repository's bundled runtime dependencies. The simulation disables process signal control so it cannot signal unrelated local processes; real VM process freezing still requires staging validation.

## Limits of these results

- A fresh `mvn clean verify` dependency resolution was blocked because the environment's configured Maven network proxy was unavailable. The full JUnit/Surefire suite and a fresh Maven dependency download were **not** run here. Run `bash scripts/build.sh` in your normal build environment; it uses `mvn clean verify` and refreshes the runnable JAR.
- No live Fly or Tigris resources were provisioned, deleted or billed. No SMS delivery was tested. No Docker images were built or published. Complete a credentialed staging save/delete/restore cycle with the rebuilt images before applying the policy to important working data.
- No new vulnerability scan or visual browser audit was performed. This patch does not implement the deferred environment maintenance worker or adaptive optimizer.
- Existing provider-side outages, region capacity, arbitrary startup scripts and provider logs arriving after shutdown are not solved by local tests. The 180-second limit is a planning guard and failure deadline, not a guarantee of successful recovery.
- One backend replica is required. The migration is additive; old retained Machines are protected rather than silently discarded. Legacy agent images lack the required final log-flush acknowledgement and block deletion until updated.
- Displayed costs remain configured-rate machine/volume estimates, not complete provider invoices.

The source patch excludes rebuilt binary JARs and generated UI assets. Rebuild from patched source. The packaged application instructions explain upgrade order and preservation of existing work.
