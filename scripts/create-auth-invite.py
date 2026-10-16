#!/usr/bin/env python3
"""Offline generation only. Writes a secret invite and Fly JSON to private files; no API calls."""
import argparse, datetime, hashlib, json, os, pathlib, secrets

p = argparse.ArgumentParser()
p.add_argument('--output', required=True, help='New directory, outside the repository')
p.add_argument('--hours', type=int, default=24)
p.add_argument('--existing-json', help='Optional existing hash/expiry JSON to retain unused invites')
a = p.parse_args()
if not 1 <= a.hours <= 720:
    p.error('--hours must be 1..720')
directory = pathlib.Path(a.output).expanduser().resolve()
os.umask(0o077)
directory.mkdir(mode=0o700, parents=False, exist_ok=False)
entries = json.loads(pathlib.Path(a.existing_json).read_text()) if a.existing_json else {}
if not isinstance(entries, dict) or len(entries) >= 1000:
    p.error('Existing JSON must be an object with fewer than 1000 entries')
code = secrets.token_urlsafe(32)
expiry = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=a.hours)).isoformat().replace('+00:00', 'Z')
entries[hashlib.sha256(code.encode()).hexdigest()] = expiry
(directory / 'invite.txt').write_text(code + '\n')
(directory / 'fly-invites.json').write_text(json.dumps(entries, separators=(',', ':')) + '\n')
print('Created invite.txt and fly-invites.json. Keep these outside git. Invite expires at ' + expiry)
