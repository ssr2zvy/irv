#!/bin/sh
set -eu

# Volume mounts hide image-time ownership; prepare only application directories.
data_root="${IRV_DATA_DIR:-/data}"
case "$data_root" in
  /*) ;;
  *) echo 'IRV_DATA_DIR must be absolute' >&2; exit 1 ;;
esac
[ "$data_root" != / ] || exit 1
[ "$(realpath -m -- "$data_root")" = "$data_root" ] || exit 1
for directory in "$data_root" "$data_root/db" "$data_root/blobs" "$data_root/private"; do
  [ ! -L "$directory" ] || exit 1
  mkdir -p -- "$directory"
  if [ "$(id -u)" = 0 ]; then chown irv:irv -- "$directory"; fi
done
chmod 700 "$data_root/private"
if [ "$(id -u)" = 0 ]; then
  exec gosu irv "$0" "$@"
fi

# Only the explicit private-test profile may generate a local persistent key.
# Normal deployments retain Crypto's requirement for IRV_ENCRYPTION_KEY.
if [ "${SPRING_PROFILES_ACTIVE:-}" = first-deploy ]; then
  umask 077
  if [ -z "${IRV_ENCRYPTION_KEY:-}" ]; then
    key_file="$data_root/private/encryption.key"
    [ ! -L "$key_file" ] || exit 1
    if [ ! -e "$key_file" ]; then
      (set -C; openssl rand -base64 32 > "$key_file")
    fi
    [ -s "$key_file" ] || exit 1
    chmod 600 "$key_file"
    IRV_ENCRYPTION_KEY="$(cat "$key_file")"
    export IRV_ENCRYPTION_KEY
  fi
  if [ -z "${IRV_BOOTSTRAP_INVITE:-}" ]; then
    invite_file="$data_root/private/bootstrap-invite"
    [ ! -L "$invite_file" ] || exit 1
    if [ ! -e "$invite_file" ]; then
      (set -C; openssl rand -hex 32 > "$invite_file")
    fi
    [ -s "$invite_file" ] || exit 1
    chmod 600 "$invite_file"
    IRV_BOOTSTRAP_INVITE="$(cat "$invite_file")"
    export IRV_BOOTSTRAP_INVITE
  fi
  echo 'Private first-deploy mode: embedded H2 and local files; external services are not configured.' >&2
fi
exec "$@"
