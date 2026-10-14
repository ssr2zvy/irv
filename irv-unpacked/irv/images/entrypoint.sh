#!/bin/sh
set -eu
# A Fly volume is root-owned on first mount. Only the workspace mount is assigned to dev.
if [ "${IRV_WORKSPACE_BASE:-/home/dev}" = /persist ]; then
  mkdir -p /persist
  chown dev:dev /persist
fi
mkdir -p /tmp/irv-runtime
chown dev:dev /tmp/irv-runtime
exec setpriv --reuid=dev --regid=dev --init-groups --no-new-privs python3 /opt/irv/agent.py
