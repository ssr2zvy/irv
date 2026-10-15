#!/usr/bin/python3
"""Deployment gate: verify Java AND the tunnel without printing secret material."""
import time
import urllib.request
import sys

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    try:
        with opener.open("http://127.0.0.1:8080/api/health", timeout=3) as response:
            if response.status != 200:
                raise ValueError("not ready")
        print("Java and Cloudflare connector are ready")
        sys.exit(0)
    except (OSError, ValueError):
        time.sleep(2)
print("Readiness failed: Java or the Cloudflare connector is unavailable", file=sys.stderr)
sys.exit(1)
