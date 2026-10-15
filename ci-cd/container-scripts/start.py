#!/usr/bin/python3
"""Root-only initialization, then supervise two independently unprivileged services."""
import grp
import os
import pathlib
import sys

JAVA = ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/irv.jar"]


def main():
    token = os.environ.pop("TUNNEL_TOKEN", "")
    if os.environ.get("IRV_TUNNEL_ENABLED", "false") != "true":
        os.execvpe("/usr/local/bin/irv-entrypoint",
                   ["irv-entrypoint", *sys.argv[1:]], dict(os.environ))
    if os.geteuid() != 0:
        raise ValueError("Tunnel startup requires the container initialization user")
    if not token or any(c.isspace() for c in token):
        raise ValueError("Set a nonempty TUNNEL_TOKEN Fly secret on iruvu")
    if sys.argv[1:] != JAVA:
        raise ValueError("Tunnel mode uses the image's standard Java command")
    gid = grp.getgrnam("cloudflared").gr_gid
    directory = pathlib.Path("/run/irv-tunnel")
    if directory.is_symlink():
        raise ValueError("Invalid tunnel runtime directory")
    directory.mkdir(mode=0o750, exist_ok=True)
    os.chown(directory, 0, gid)
    os.chmod(directory, 0o750)
    # Ephemeral rootfs, never /data. Java's user cannot traverse this directory.
    fd = os.open(directory / "token", os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o640)
    with os.fdopen(fd, "w") as output:
        os.fchown(output.fileno(), 0, gid)
        os.fchmod(output.fileno(), 0o640)
        output.write(token)
    token = None
    # exec replaces this root process; neither Supervisor nor Java inherits the token.
    os.execve("/usr/bin/supervisord", ["supervisord", "-n", "-c", "/etc/irv-supervisord.conf"], dict(os.environ))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        # Never print environment values or the supplied token.
        print("Container initialization failed: " + str(error), file=sys.stderr)
        sys.exit(1)
