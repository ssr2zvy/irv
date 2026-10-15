#!/usr/bin/env python3
"""Real HTTP smoke checks against a JAR (or explicitly supplied compiled classpath)."""
import argparse, http.client, json, os, pathlib, subprocess, tempfile, time, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

parser = argparse.ArgumentParser()
group = parser.add_mutually_exclusive_group(required=True)
group.add_argument('--jar'); group.add_argument('--classpath')
a = parser.parse_args()
base = ['java'] + (['-jar', str(pathlib.Path(a.jar).resolve())] if a.jar else ['-cp', a.classpath, 'dev.irv.App'])
secret = 'a' * 64
fingerprint = 'b' * 64

def request(port, path, headers=None, method='GET'):
    c = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
    c.request(method, path, headers=headers or {})
    r = c.getresponse(); result = r.status, dict(r.getheaders()), r.read(); c.close(); return result

class Ready(BaseHTTPRequestHandler):
    healthy = True
    def do_GET(self):
        self.send_response(200 if self.healthy else 503); self.end_headers()
    def log_message(self, *args): pass

server = HTTPServer(('127.0.0.1', 2000), Ready)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
 for configured in [False, True]:
    env = {k:v for k,v in os.environ.items() if not k.startswith(('IRV_', 'SPRING_', 'DATABASE_', 'S3_', 'AWS_', 'FLY_'))}
    env.update(SPRING_PROFILES_ACTIVE='auth-only', IRV_TUNNEL_ENABLED='true')
    if configured: env.update(IRV_AUTH_INGRESS_SECRET=secret, IRV_AUTH_ALLOWED_CERT_SHA256=fingerprint)
    with tempfile.TemporaryDirectory() as work:
     with open(pathlib.Path(work)/'run.log','w+') as log:
      process = subprocess.Popen(base, env=env, cwd=work, stdout=log, stderr=log)
      try:
        for attempt in range(120):
            if process.poll() is not None:
                log.seek(0); raise AssertionError(log.read())
            try:
                if request(8080, '/api/health')[0] == 200: break
            except OSError: pass
            time.sleep(.25)
        else: raise AssertionError('Startup timeout')
        status, headers, body = request(8081, '/')
        assert status == 200 and b'Authentication diagnostics' in body
        assert 'Set-Cookie' not in headers and headers['Cache-Control'] == 'no-store'
        assert request(8081,'/auth/app.js')[0] == 200
        for port, paths in [(8080,['/','/auth/status','/api/sandboxes','/api/login','/agent','/ws','/assets/index.js']), (8081,['/api/health','/health','/api/login','/api/sandboxes','/agent','/ws','/index.html','/error','/auth/callback'])]:
            for path in paths: assert request(port,path)[0] == 404, (port,path)
        assert request(8081,'/auth/status',method='POST')[0] == 405
        assert request(8080,'/api/health',method='POST')[0] == 405
        spoof={'X-Irv-Cert-Verified':'true','X-Irv-Cert-Revoked':'false','X-Irv-Cert-SHA256':fingerprint,'Cookie':'irv=fake','Authorization':'Bearer fake','X-Forwarded-Port':'8081'}
        def checks(h):
            response=request(8081,'/auth/status',h)
            data=json.loads(response[2]); assert data['authenticated'] is False
            assert len(data['checks']) == 9
            assert secret.encode() not in response[2] and fingerprint.encode() not in response[2]
            return {x['id']:x['status'] for x in data['checks']}
        states=checks(spoof)
        assert states['certificate'] == ('failed' if configured else 'not_configured')
        spoof['X-Irv-Ingress-Key']=secret
        states=checks(spoof)
        assert states['certificate'] == ('passed' if configured else 'not_configured')
        if configured:
            assert states['approval']=='passed'
            for name, value in [('X-Irv-Cert-Revoked','true'),('X-Irv-Cert-Verified','false'),('X-Irv-Cert-SHA256','invalid'),('X-Irv-Ingress-Key','wrong')]:
                h={**spoof,name:value}; assert checks(h)['certificate']=='failed'
            assert checks({**spoof,'X-Irv-Cert-SHA256':'c'*64})['approval']=='failed'
            c=http.client.HTTPConnection('127.0.0.1',8081)
            c.putrequest('GET','/auth/status')
            for k,v in spoof.items(): c.putheader(k,v)
            c.putheader('X-Irv-Cert-Verified','true'); c.endheaders()
            data=json.loads(c.getresponse().read()); c.close()
            assert next(x for x in data['checks'] if x['id']=='certificate')['status']=='failed'
        assert states['password']==states['passkey']==states['session']=='not_implemented'
        Ready.healthy=False
        assert request(8080,'/api/health')[0] == 503
        Ready.healthy=True
        assert request(8080,'/api/health')[0] == 200
        assert not (pathlib.Path(work)/'data').exists(), 'Auth-only created legacy data'
        print('PASS:', 'configured certificate checks' if configured else 'unconfigured fail-closed diagnostics', flush=True)
      finally:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
finally:
 server.shutdown();server.server_close()
