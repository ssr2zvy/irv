#!/bin/sh
set -eu
# Only the broker remains root. Terminal/setup/process children drop to dev.
export IRV_WORKSPACE_BASE="${IRV_WORKSPACE_BASE:-/persist}"
export IRV_RUNTIME="${IRV_RUNTIME:-/persist/.irv-runtime}"
[ ! -L "$IRV_WORKSPACE_BASE" ] && [ ! -L "$IRV_RUNTIME" ]
mkdir -p "$IRV_WORKSPACE_BASE" "$IRV_RUNTIME"
chown root:root "$IRV_WORKSPACE_BASE" "$IRV_RUNTIME"
chmod 755 "$IRV_WORKSPACE_BASE" "$IRV_RUNTIME"
exec setpriv --no-new-privs python3 /opt/irv/agent.py
