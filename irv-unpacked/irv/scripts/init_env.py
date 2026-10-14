#!/usr/bin/env python3
import base64,secrets,pathlib,os
root=pathlib.Path(__file__).resolve().parents[1]
p=root/'.env'
if p.exists():raise SystemExit('.env already exists; refusing to replace secrets')
s=(root/'.env.example').read_text()
for key in ['IRV_ENCRYPTION_KEY','IRV_BOOTSTRAP_INVITE','IRV_MAINTENANCE_TOKEN','DATABASE_PASSWORD']:
 value=base64.b64encode(secrets.token_bytes(32)).decode() if key=='IRV_ENCRYPTION_KEY' else secrets.token_urlsafe(32)
 s=s.replace(key+'=GENERATE_WITH_scripts/init_env.py',key+'='+value)
p.write_text(s);os.chmod(p,0o600)
print('Created .env. Read IRV_BOOTSTRAP_INVITE there to register the first administrator within 24 hours of first boot.')
