#!/usr/bin/python3
"""Stop the whole supervised service if either child exhausts startup retries."""
import os
import signal
import sys

while True:
    sys.stdout.write("READY\n")
    sys.stdout.flush()
    line = sys.stdin.buffer.readline()
    if not line:
        sys.exit(1)
    headers = dict(part.split(":", 1) for part in line.decode().split())
    sys.stdin.buffer.read(int(headers["len"]))
    sys.stdout.write("RESULT 2\nOK")
    sys.stdout.flush()
    if headers.get("eventname") == "PROCESS_STATE_FATAL":
        print("Required service failed startup; stopping both services for Fly restart", file=sys.stderr)
        os.kill(os.getppid(), signal.SIGTERM)
