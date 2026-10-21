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
    sensitive = [secret, fp, "audit-query-secret", "audit-header-secret", "audit-path-secret"]
    if configured: env.update(IRV_AUTH_INGRESS_SECRET=secret)
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
            if not configured:
                checks = {check['id']: check for check in report['checks']}
                assert checks['certificate']['status'] == 'not_configured'
                assert checks['approval']['status'] == 'not_configured'
            assert secret.encode() not in body and fp.encode() not in body
            status, h, body = request(8081, '/auth/csrf', headers)
            if configured:
                assert status == 200
                cookie = h['Set-Cookie']
                assert cookie.startswith('__Host-irv=')
                for flag in ['Path=/', 'Secure', 'HttpOnly', 'SameSite=Lax']: assert flag in cookie, cookie
                assert 'Domain=' not in cookie
                assert json.loads(body)['token']
                sensitive.extend([json.loads(body)['token'], cookie.split(';')[0].split('=', 1)[1]])
                assert request(8081, '/auth/protected', headers)[0] == 401
            else: assert status == 403
            assert request(8081, '/auth/csrf', {**headers, 'X-Irv-Ingress-Key': '0'*64})[0] == 403
            assert request(8081, '/oauth2/authorization/zitadel', headers)[0] == 405
            assert request(8081, '/auth/activity', headers, 'POST')[0] in [401,403]
            assert request(8081, '/audit-path-secret?code=audit-query-secret', {**headers, 'Authorization': 'Bearer audit-header-secret'})[0] == 404
            assert not list(pathlib.Path(work).rglob('*.mv.db'))
            print('PASS: real HTTP isolation, fail-closed configuration and cookie flags; configured=' + str(configured))
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.flush(); log.seek(0)
        output = log.read()
        rows = [json.loads(line) for line in output.splitlines() if line.startswith('{"schema":"irv.auth.v1"')]
        assert rows, 'No JSON audit output'
        encoded = json.dumps(rows)
        for value in sensitive: assert value not in encoded, 'Sensitive data in audit output'
        assert any(r['reason'] == 'route_not_exposed' for r in rows)
        assert any(r['event'] == 'ingress' and r['outcome'] == 'failed' for r in rows)
        completed = [r for r in rows if r['event'] == 'request' and 'status' in r]
        started = [r for r in rows if r['event'] == 'request' and r['outcome'] == 'started']
        assert len(completed) == len(started), 'Missing or duplicate completion'
        assert len({r['request_id'] for r in completed}) == len(completed)
        assert all(r['duration_ms'] >= 0 for r in completed)
        print('PASS: real stdout JSON audit, early rejection, request pairing and secret exclusion')
