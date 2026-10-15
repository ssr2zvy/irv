# Validation of the sync and logs patch

This report concerns the source patch based on GitHub main `2832a7f36eb77513a12dad8565d3f56903270f6f`. Tests use temporary data; no live Fly/Tigris deployment was performed.

## Executed successfully

- Compiled all Java production sources using Java 17 and the existing repository runtime dependency JARs.
- 21 standalone Java sync/log/migration checks: competing backend instances, one lease winner, surface separation, takeover fencing, expired lease rejection, durable invalid drafts, valid revision commits, request replay, mismatched replay rejection, recovery after service recreation, ordered pending revisions, hash acknowledgements, log replay/deduplication, original timestamps/session/stream, whole-batch rollback, running-machine migration refusal, volume retention during image migration and image selection.
- 20 existing standalone Java lifecycle checks, including final log acknowledgement before deletion, failed-save retention and retry, cancellation, volume cleanup and cold startup limits.
- 16 Python tests discovered: 14 passed and 2 explicitly skipped because this host denies UID/GID switching and Unix-domain sockets. Passing tests cover path restrictions, incremental save/restore, failed uploads, final log flushing, concurrent uploader behavior, replay after restart, missing acknowledgements, config acknowledgement retry, separate stdout/stderr, symlink rejection and file replacement/unmanage/delete semantics.
- Both existing frontend helper tests passed, and Vite built the updated React UI.
- Real Spring backend plus real agent against simulated Fly and filesystem blob storage: signup/TOTP, PTY, guarded app edits, guarded terminal-protocol edits and the actual CLI with a scripted editor, competing app rejection, acknowledgement visibility, HTTP/WebSocket previews, stdout/stderr logs, verified final save/flush, machine-and-volume deletion, fresh restore, and preservation of later edits to an unmanaged helper.
- Patch preflight/application and after-file checksums are validated on a clean source checkout, including already-applied detection.

## Scope of simulation and remaining deployment checks

The local end-to-end fixture disables process signal control and substitutes UID switching and Unix-socket transport with ordinary local execution and loopback HTTP. The production code retains privilege dropping and a protected Unix socket; the fixture is not copied into images. This host cannot establish real Linux cross-user enforcement. The included permission and socket tests must be run in the sandbox image before deployment.

The local runnable JAR was assembled temporarily from freshly compiled classes/resources and the existing repository runtime dependencies. It is not shipped in the patch. `mvn -o test` could not resolve the uncached Spring Boot parent POM; a full fresh Maven/Surefire run was not completed here. Run `bash scripts/build.sh` in the normal build environment. Java standalone harnesses also have JUnit wrappers for that build.

No live Fly API/container-log feed, Tigris service, PostgreSQL server, SMS provider, image build or interactive browser rendering was exercised here. Provider log polling is explicitly best-effort and distinct from the agent's acknowledged replay protocol. Use the staging checklist in `README_APPLY.md` before production.
