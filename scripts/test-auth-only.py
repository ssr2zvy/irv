#!/usr/bin/env python3
"""Real HTTP smoke checks for isolated auth runtime and actual servlet cookie attributes."""
import argparse, http.client, json, os, pathlib, subprocess, tempfile, time
p = argparse.ArgumentParser()
g = p.add_mutually_exclusive_group(required=True)
g.add_argument('--jar'); g.add_argument('--classpath')
a = p.parse_args()
base = ['java'] + (['-jar', str(pathlib.Path(a.jar).resolve())] if a.jar else ['-cp', a.classpath, 'dev.irv.App'])
secret, fp = 'a' * 64, 'b' * 64
headers = {'X-Irv-Ingress-Key': secret, 'X-Irv-Cert-Verified': 'true', 'X-Irv-Cert-Revoked': 'false', 'X-Irv-Cert-SHA256': fp}
def request(port, path, headers=None, method='GET'):
    c = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
    c.request(method, path, headers=headers or {})
    r = c.getresponse(); result = r.status, dict(r.getheaders()), r.read(); c.close(); return result
for configured in [False, True]:
    env = {k:v for k,v in os.environ.items() if not k.startswith(('IRV_', 'SPRING_', 'DATABASE_', 'S3_', 'AWS_', 'FLY_'))}
    env.update(SPRING_PROFILES_ACTIVE='auth-only', IRV_TUNNEL_ENABLED='false')
    if configured: env.update(IRV_AUTH_INGRESS_SECRET=secret, IRV_AUTH_ADMISSIONS_JSON=json.dumps({'123': [fp]}))
    with tempfile.TemporaryDirectory() as work:
      with open(pathlib.Path(work)/'run.log', 'w+') as log:
        process = subprocess.Popen(base, env=env, cwd=work, stdout=log, stderr=log)
        try:
            for _ in range(120):
                if process.poll() is not None:
                    log.seek(0); raise AssertionError(log.read())
                try:
                    if request(8080, '/api/health')[0] == 200: break
                except OSError: pass
                time.sleep(.25)
            else: raise AssertionError('Startup timeout')
            for port, paths in [(8080, ['/', '/auth/status', '/auth/csrf', '/login/oauth2/code/zitadel']),
                                (8081, ['/api/health', '/api/sandboxes', '/api/login', '/ws', '/agent', '/error'])]:
                for path in paths: assert request(port, path)[0] == 404, (port, path)
            for path in ['/', '/auth/app.js', '/auth/style.css']:
                status, h, body = request(8081, path)
                assert status == 200 and h['Cache-Control'] == 'no-store'
                assert 'Set-Cookie' not in h
            status, h, body = request(8081, '/auth/status', headers)
            report = json.loads(body)
            assert status == 200 and report['authenticated'] is False and len(report['checks']) == 8
            assert secret.encode() not in body and fp.encode() not in body
            status, h, body = request(8081, '/auth/csrf', headers)
            if configured:
                assert status == 200
                cookie = h['Set-Cookie']
                assert cookie.startswith('__Host-irv=')
                for flag in ['Path=/', 'Secure', 'HttpOnly', 'SameSite=Lax']: assert flag in cookie, cookie
                assert 'Domain=' not in cookie
                assert json.loads(body)['token']
                assert request(8081, '/auth/protected', headers)[0] == 401
            else: assert status == 403
            assert request(8081, '/auth/csrf', {**headers, 'X-Irv-Ingress-Key': '0'*64})[0] == 403
            assert request(8081, '/oauth2/authorization/zitadel', headers)[0] == 405
            assert request(8081, '/auth/activity', headers, 'POST')[0] in [401,403]
            assert not list(pathlib.Path(work).rglob('*.mv.db'))
            print('PASS: real HTTP isolation, fail-closed configuration and cookie flags; configured=' + str(configured))
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
