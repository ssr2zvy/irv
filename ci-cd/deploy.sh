#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY="ssr2zvy/irv"
cd "$REPO_DIR"

usage() {
  printf '%s\n' \
    'Usage: ci-cd/deploy.sh deploy | stop | cancel RUN_ID | help' \
    'deploy: Build and deploy this checkout using FLY_API_TOKEN.' \
    'stop: Stop the deployed app Machines; preserve volumes and data.' \
    'cancel RUN_ID: Cancel a GitHub deployment workflow run using gh authentication.' \
    'Interrupt local deploys with Ctrl+C. Cancellation is not a rollback.'
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

action="${1:-help}"
case "$action" in
  help|--help|-h)
    usage
    exit 0
    ;;
  deploy|stop)
    [[ $# -eq 1 ]] || fail "Expected only '$action'. See help."
    ;;
  cancel)
    [[ $# -eq 2 && "$2" =~ ^[1-9][0-9]*$ ]] || fail 'Use cancel RUN_ID with a numeric GitHub Actions run ID.'
    require_command gh
    # Verify the target before cancelling; never cancel unrelated repository workflows.
    run="$(gh api "repos/$REPOSITORY/actions/runs/$2" \
      --jq '[.path, .head_branch, .status] | @tsv')" ||
      fail 'Cannot read the run. Authenticate gh with Actions read/write access.'
    IFS=$'\t' read -r workflow branch status <<< "$run"
    [[ "$workflow" == ".github/workflows/deploy.yml" && ( "$branch" == "main" || "$branch" == deploy/* ) ]] ||
      fail 'Refusing to cancel a run outside the main or deployment-tag workflow.'
    [[ "$status" != "completed" ]] || fail 'That run has already completed; there is nothing to cancel.'
    printf '%s\n' 'Cancelling the workflow does not undo an image or Machine update already applied.'
    exec gh run cancel "$2" --repo "$REPOSITORY"
    ;;
  *)
    usage >&2
    fail "Unknown command: $action"
    ;;
esac

require_command flyctl
[[ -n "${FLY_API_TOKEN:-}" ]] ||
  fail 'FLY_API_TOKEN is missing. Add an app-scoped Fly deploy token as a Codespaces secret for this repository and restart the Codespace, or securely export it locally.'
[[ -f fly.toml ]] || fail 'Missing repository-root fly.toml.'
if [[ "$action" == "deploy" ]]; then
  [[ -f ci-cd/Containerfile ]] || fail 'Missing ci-cd/Containerfile.'
else
  require_command jq
fi

flyctl status --config fly.toml --json >/dev/null ||
  fail 'Cannot access the Fly app. Check token expiry, app permissions, and network connectivity; no deployment or stop was attempted.'

if [[ "$action" == "deploy" ]]; then
  printf '%s\n' 'Deploying this checkout. Production deployments should use the merged main revision.'
  build_revision="$(git rev-parse HEAD)"
  build_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  build_id="${GITHUB_RUN_ID:-$(date -u +%Y%m%d%H%M%S)}.${GITHUB_RUN_ATTEMPT:-1}"
  flyctl deploy . --config fly.toml --remote-only --ha=false --wait-timeout 5m \
    --build-arg "BUILD_REVISION=$build_revision" --build-arg "BUILD_ID=$build_id" --build-arg "BUILD_TIMESTAMP=$build_timestamp"
  # The one Machine contains both services. Do not report success until both are ready.
  exec flyctl ssh console --config fly.toml -C /usr/local/bin/irv-wait-ready
fi

machines="$(flyctl machine list --config fly.toml --json)" ||
  fail 'Cannot list Machines; no stop was attempted.'
ids="$(jq -er '
  if type != "array" then error("Expected a Machine array")
  elif any(.[]; (.id | type) != "string" or (.id | test("^[a-fA-F0-9]+$") | not))
  then error("Invalid Machine ID")
  else [.[] | select(.state != "destroyed") | .id] | join("\n")
  end
' <<< "$machines")" || fail 'Invalid Machine response; no stop was attempted.'
if [[ -z "$ids" ]]; then
  printf '%s\n' 'No Machines to stop.'
  exit 0
fi
mapfile -t machine_ids <<< "$ids"
printf '%s\n' 'Stopping app Machines. Volumes remain intact; storage charges continue.'
exec flyctl machine stop "${machine_ids[@]}" --config fly.toml --wait-timeout 5m
