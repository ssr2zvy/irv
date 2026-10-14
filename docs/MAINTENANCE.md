# Environment maintenance: deferred

The requested daily environment-maintenance worker is **not implemented**. Its
requirements are recorded in the fully commented Java source file
`backend/src/main/java/dev/irv/EnvironmentMaintenanceWorker.java`.

The future irv worker must check the components of both predefined sandbox
images (blank and Java/frontend) for vulnerabilities and available updates,
apply necessary updates and rebuild the affected images, and monitor relevant
Fly API changes and make the necessary Machine-management integration updates.

The GitHub workflow substitute has been removed. The repository no longer
includes the maintenance, dependency auto-merge, or deployment workflows, nor
Dependabot/Renovate version-update configuration. The previously deleted CI
workflow has not been restored. No scheduler or automatic updater is enabled
by the commented source file.

## Existing runtime maintenance

`Maintenance.java` remains active for snapshot/chunk retention, expired logs
and sessions, provider metrics, and container-log collection. These operations
serve the running app and are separate from the deferred environment updater.
The lifecycle cost optimizer is also unchanged.

## Manual environment updates

Until the worker is implemented, an operator must update the image recipes and
components, build and publish images, validate them, and select the new digests.
See [Deployment](DEPLOYMENT.md) for image build/push commands and backend
deployment. `scripts/live_smoke.py` can be run manually against staging; it
creates billable sandbox resources.

The existing `POST /maintenance/promote` endpoint and `scripts/promote.py` are
manual tools. The endpoint checks the token and digest format and records image
references in the database; it does not run scans, tests, or rebuilds itself.

To select a tested pair of images, create `blank-digest.txt` and
`java-digest.txt` in your current working directory. Each file must contain the
full published reference, such as `ghcr.io/OWNER/irv-blank@sha256:...`.
Set `MAINTENANCE_TOKEN` in your protected shell environment to the target
backend's `IRV_MAINTENANCE_TOKEN`, then run:

```sh
python3 scripts/promote.py https://YOUR_IRV_BACKEND
```

The database catalog overrides the initial `IRV_BLANK_IMAGE` and
`IRV_JAVA_IMAGE` environment values. The selected pair applies at the next
cold Machine creation; this command does not replace a running or retained
Machine. To select a previous pair, repeat the same manual operation with the
previous image references. Keep the user workspace, configuration, and scripts
under the existing verified-save lifecycle.

`scripts/check_fly_contract.py` and `contracts/fly-openapi.json` remain available
for manual compatibility checks. They compare API definitions; they do not
modify the Java integration or implement automatic Fly API updates.
