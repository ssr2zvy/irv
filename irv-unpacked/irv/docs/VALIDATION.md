# Validation record

Prepared for this distribution on 2026-10-14 UTC.

## Executed successfully

- **14 Java tests**: RFC-compatible TOTP and replay rejection; restricted TOML and path validation; censored observations and return learning; exclusion of recovery plans above 180 seconds; transition cost tradeoffs; complete invite/signup/MFA/session/logout flow; invite reuse rejection; CSRF protections; active-session protection; failed-save and mismatched-snapshot deletion guards; valid stopped-to-cold transition.
- **5 Python tests**: path traversal and external symlink escape rejection; keyword validation; provider-config rejection; incremental deduplication and exact restore including empty directories/symlinks; failed-upload commit prevention.
- **2 frontend helper tests**: deadline display and defensive stored-tree parsing.
- **Real local backend-agent integration** using a simulated Fly API: boot/migrations, frontend serving, invite registration, TOTP enrollment, owner session, sandbox create/start/readiness, PTY shell execution, file writes, chunk snapshot commit, app-to-machine and machine-to-app config synchronization, temporary-host HTTP preview, WebSocket echo using the Vite HMR subprotocol, and persisted logs.
- React production build, Spring Boot executable JAR build, Python syntax checks.
- `npm audit`: **0 reported vulnerabilities** after updating Vite to 7.3.7.
- Fly API contract checker against the captured current upstream OpenAPI baseline.

## Not executed here

- Actual Fly Machine provisioning, volume retention/deletion, suspend/resume, region capacity and billing reconciliation.
- Actual Tigris S3 requests and account-level policies.
- Twilio SMS delivery/checks.
- Docker image builds, Trivy image scans, or GitHub-hosted scheduled/deployment workflows (Docker and account credentials were not available in this execution environment).
- A visual browser inspection of the UI. The production bundle and real HTTP/WebSocket paths were exercised, but screenshots/accessibility audits were not performed.

The supplied `scripts/live_smoke.py` and workflow gates are for credentialed staging validation. They do not substitute for testing failure recovery under your actual cloud configuration. Live canaries currently verify startup/PTY/write/save; full provider volume/suspend and destructive-recreation exercises must additionally be run before relying on those modes in production.

## Explicit scope limits

- One control-plane replica. Distributed locking/routing is not implemented.
- Cold-start image deployment is automatic after passing maintenance. Arbitrary breaking API fixes, unpatchable vulnerabilities, and all behavioral regressions cannot be repaired autonomously.
- The optimizer learns return timing and conservative transition-duration bounds. It does not use an LLM, paid randomized exploration, time-of-day features, or a direct reinforcement-learning policy.
- Volume retention/deletion is optimized when a volume exists; working medium at creation is an operator setting. Live migration into a newly created volume is not optimized by this release.
- The 180-second limit is an enforced planning constraint and startup failure deadline, not an infrastructure availability guarantee.
- UI cost totals are estimated machine/volume costs, not invoice totals; no exact billing API has been assumed.
- Historical backup selection and invite revocation are exposed through owner/admin APIs. Password/MFA administrator recovery is an operator procedure, not a finished account-recovery UI.
- HTTP previews are buffered with a 4 MiB limit; SSE/streamed HTTP and arbitrary WebSocket subprotocols are outside this implementation.

Consult the other documents for exact defaults. These limits are stated so the archive is reviewable; the package should not be represented as a fully validated production service merely because its local tests pass.
