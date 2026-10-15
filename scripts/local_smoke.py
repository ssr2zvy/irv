#!/usr/bin/env python3
"""Run the real Java server and Python agent, using a local fake Fly API. No cloud spend."""
import asyncio,base64,contextlib,hashlib,hmac,http.server,json,os,pathlib,secrets,signal,struct,subprocess,tempfile,threading,time,urllib.parse,shutil
import aiohttp
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAR=ROOT/'backend/target/irv-0.1.0.jar'

def otp(secret):
 d=hmac.new(base64.b32decode(secret),struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();o=d[-1]&15
 return str((struct.unpack('>I',d[o:o+4])[0]&0x7fffffff)%1000000).zfill(6)
async def main():
 with tempfile.TemporaryDirectory(prefix='irv-smoke-') as tmp:
  d=pathlib.Path(tmp);agents=[];machines={};machine_processes={};apps=set();agent_logs=[];provider_deletes=[]
  class FlyMock(http.server.BaseHTTPRequestHandler):
   def log_message(self,*args):pass
   def do_GET(self):
    if '/machines/' in self.path:self.reply(machines.get(self.path.split('/')[-1],{}))
    elif self.path in apps:self.reply({'name':self.path.split('/')[-1]})
    else:self.reply({},404)
   def do_POST(self):
    body=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))) or '{}')
    if self.path=='/v1/apps':apps.add('/v1/apps/'+body['app_name']);self.reply({'id':'testapp'});return
    if self.path.endswith('/volumes'):self.reply({'id':'test-volume'});return
    if self.path.endswith('/machines'):
     mid=secrets.token_hex(7);env={**os.environ,**body['config']['env'],'IRV_ALLOW_PROCESS_CONTROL':'0','IRV_HOME':str(d/'home'),'IRV_WORKSPACE_BASE':str(d/'work'),'IRV_RUNTIME':str(d/'runtime')}
     (d/'home').mkdir(exist_ok=True);log=open(d/f'agent-{mid}.log','w');agent_logs.append(log)
     p=subprocess.Popen(['python3',str(ROOT/'agent/agent.py')],env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);agents.append(p);machine_processes[mid]=p;machines[mid]={'id':mid,'state':'started','events':[]};self.reply(machines[mid]);return
    self.reply({})
   def do_DELETE(self):
    path=urllib.parse.urlsplit(self.path).path
    if '/machines/' in path:
     mid=path.split('/')[-1];p=machine_processes.pop(mid,None)
     if p:
      with contextlib.suppress(ProcessLookupError):os.killpg(p.pid,signal.SIGTERM)
      with contextlib.suppress(Exception):p.wait(timeout=5)
     machines.pop(mid,None);provider_deletes.append('machine')
    elif '/volumes/' in path:
     shutil.rmtree(d/'work',ignore_errors=True);provider_deletes.append('volume')
    self.reply({})
   def reply(self,obj,status=200):
    out=json.dumps(obj).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(out)));self.end_headers();self.wfile.write(out)
  mock=http.server.ThreadingHTTPServer(('127.0.0.1',0),FlyMock);threading.Thread(target=mock.serve_forever,daemon=True).start()
  import socket
  sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1];sock.close();origin=f'http://localhost:{port}'
  invite=secrets.token_urlsafe(32)
  env={**os.environ,'PORT':str(port),'IRV_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),'IRV_BOOTSTRAP_INVITE':invite,'IRV_SECURE_COOKIES':'false','IRV_PUBLIC_ORIGIN':origin,'IRV_PREVIEW_ORIGIN':origin,'DATABASE_URL':f'jdbc:h2:file:{d}/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE','IRV_BLOB_DIR':str(d/'blobs'),'FLY_API_URL':f'http://127.0.0.1:{mock.server_port}/v1','FLY_API_TOKEN':'local-test-only','FLY_ORG':'','IRV_BLANK_IMAGE':'test:blank','IRV_JAVA_IMAGE':'test:java','S3_BUCKET':''}
  log=open(d/'server.log','w');server=subprocess.Popen(['java','-jar',str(JAR)],env=env,stdout=log,stderr=subprocess.STDOUT)
  try:
   async with aiohttp.ClientSession(cookie_jar=aiohttp.CookieJar(unsafe=True),headers={'X-Irv-Request':'1','Origin':origin}) as client:
    async def api(path,method='GET',body=None):
     async with client.request(method,origin+'/api'+path,json=body) as r:
      text=await r.text()
      if r.status>=300:raise RuntimeError(f'{path} {r.status}: {text}')
      return json.loads(text)
    for _ in range(100):
     try:await api('/health');break
     except Exception:await asyncio.sleep(.2)
    else:raise RuntimeError('Backend failed to boot')
    async with client.get(origin+'/') as r:assert r.status==200;assert 'irv' in await r.text()
    await api('/auth/register','POST',{'username':'smoke','password':'long-test-password','invite':invite})
    enroll=await api('/auth/enroll','POST',{'factor':'totp'});mfa=await api('/auth/verify','POST',{'code':otp(enroll['secret'])});assert len(mfa['recoveryCodes'])==10
    box=await api('/sandboxes','POST',{'name':'Local smoke','image':'blank'});prefix='/sandboxes/'+box['id']
    await api(prefix+'/open','POST')
    for _ in range(100):
     box=await api(prefix)
     if box['state']=='running':break
     await asyncio.sleep(.3)
    else:raise RuntimeError('Agent did not become ready: '+json.dumps(box))
    async with client.ws_connect(origin.replace('http','ws')+'/ws/terminal/'+box['id']) as ws:
     # Wait for initial PTY so input cannot race shell creation.
     await asyncio.wait_for(ws.receive(),5)
     await ws.send_json({'type':'input','data':base64.b64encode(b"printf 'persisted-value' > example.txt; printf '\\x49\\x52\\x56\\x5f\\x54\\x45\\x53\\x54\\x5f\\x4f\\x4b\\n'\n").decode()})
     out=''
     async with asyncio.timeout(10):
      while 'IRV_TEST_OK' not in out:
       msg=await ws.receive_json()
       if msg.get('type')=='terminal':out+=base64.b64decode(msg['data']).decode(errors='replace')
    assert (d/'work/workspace/example.txt').read_text()=='persisted-value'
    await api(prefix+'/save','POST')
    for _ in range(100):
     snaps=await api(prefix+'/snapshots')
     if snaps:break
     await asyncio.sleep(.2)
    assert snaps,'Missing snapshot'
    box=await api(prefix);files=json.loads(box['files']);files['scripts/helper.sh']='export DEMO=ok\n'
    await api(prefix+'/config','PUT',{'revision':box['revision'],'config':box['config'],'files':files})
    for _ in range(50):
     if (d/'work/workspace/scripts/helper.sh').exists():break
     await asyncio.sleep(.1)
    assert (d/'work/workspace/scripts/helper.sh').read_text()=='export DEMO=ok\n'
    # Local edit reflected in backend.
    (d/'work/workspace/scripts/helper.sh').write_text('export DEMO=changed\n')
    for _ in range(50):
     box=await api(prefix)
     if 'changed' in json.loads(box['files']).get('scripts/helper.sh',''):break
     await asyncio.sleep(.2)
    assert 'changed' in json.loads(box['files'])['scripts/helper.sh']
    # HTTP preview forwards to a real loopback server through the agent tunnel.
    preview_server=http.server.ThreadingHTTPServer(('127.0.0.1',0),type('PreviewHandler',(http.server.BaseHTTPRequestHandler,),{'do_GET':lambda self:(self.send_response(200),self.end_headers(),self.wfile.write(b'preview-ok')),'log_message':lambda *a:None}))
    threading.Thread(target=preview_server.serve_forever,daemon=True).start();preview_port=preview_server.server_port
    config=box['config'].replace('8080, 5173',f'8080, 5173, {preview_port}')
    await api(prefix+'/config','PUT',{'revision':box['revision'],'config':config,'files':json.loads(box['files'])});await asyncio.sleep(.5)
    grant=await api(prefix+'/preview','POST',{'port':preview_port});host=urllib.parse.urlsplit(grant['url']).netloc
    async with client.get(origin+'/test',headers={'Host':host}) as r:assert r.status==200,(r.status,await r.text());assert await r.text()=='preview-ok'
    preview_server.shutdown()
    from aiohttp import web
    async def echo(request):
     socket=web.WebSocketResponse(protocols=('vite-hmr',));await socket.prepare(request)
     async for frame in socket:
      if frame.type==aiohttp.WSMsgType.TEXT:await socket.send_str('echo:'+frame.data)
     return socket
    webapp=web.Application();webapp.router.add_get('/echo',echo);runner=web.AppRunner(webapp);await runner.setup();site=web.TCPSite(runner,'127.0.0.1',0);await site.start();wsport=site._server.sockets[0].getsockname()[1]
    box=await api(prefix);config=box['config'].replace('8080, 5173',f'8080, 5173, {wsport}')
    await api(prefix+'/config','PUT',{'revision':box['revision'],'config':config,'files':json.loads(box['files'])});await asyncio.sleep(.5)
    grant=await api(prefix+'/preview','POST',{'port':wsport});host=urllib.parse.urlsplit(grant['url']).netloc
    async with client.ws_connect(origin.replace('http','ws')+'/echo',headers={'Host':host},protocols=('vite-hmr',)) as ws:
     await ws.send_str('hello');msg=await asyncio.wait_for(ws.receive(),10);assert msg.data=='echo:hello',msg
    await runner.cleanup()

    logs=await api(prefix+'/logs');assert logs
    # Exercise the same final-save/cleanup operation used by the fixed timer, without
    # waiting five minutes. Timing, cancellation and retries are covered in Java checks.
    await api(prefix+'/archive','POST')
    for _ in range(100):
     box=await api(prefix)
     if box['state']=='archived':break
     await asyncio.sleep(.2)
    assert box['state']=='archived',box
    assert box['machine_id'] is None and box['volume_id'] is None
    assert provider_deletes==['machine','volume'],provider_deletes
    assert not (d/'work').exists(),'Fake provider did not discard working volume'
    await api(prefix+'/open','POST')
    for _ in range(100):
     box=await api(prefix)
     if box['state']=='running':break
     await asyncio.sleep(.3)
    assert box['state']=='running',box
    assert (d/'work/workspace/example.txt').read_text()=='persisted-value'
    print('PASS: verified final save, log flush, machine then volume release, new volume and cold workspace restore')
    print('PASS: real server + agent: signup, TOTP, owner session, sandbox creation, startup readiness, browser PTY, chunk save, bidirectional config sync, isolated-host HTTP and WebSocket previews, persisted logs, frontend serving')
  except Exception:
   print((d/'server.log').read_text()[-6000:])
   for p in d.glob('agent-*.log'):print(p.read_text()[-4000:])
   raise
  finally:
   server.terminate()
   with contextlib.suppress(Exception):server.wait(timeout=10)
   for p in agents:
    with contextlib.suppress(ProcessLookupError):os.killpg(p.pid,signal.SIGTERM)
   mock.shutdown();log.close()
   for f in agent_logs:f.close()
asyncio.run(main())
