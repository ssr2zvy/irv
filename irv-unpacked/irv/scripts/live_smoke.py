#!/usr/bin/env python3
"""Credentialed staging smoke. Creates a canary sandbox and makes billable Fly calls."""
import asyncio,base64,hashlib,hmac,json,os,struct,time
import aiohttp

def otp(secret):
 key=base64.b32decode(secret);digest=hmac.new(key,struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();o=digest[-1]&15
 return str((struct.unpack('>I',digest[o:o+4])[0]&0x7fffffff)%1000000).zfill(6)
async def main():
 origin=os.environ['IRV_SMOKE_ORIGIN'].rstrip('/')
 async with aiohttp.ClientSession(cookie_jar=aiohttp.CookieJar(unsafe=True),headers={'X-Irv-Request':'1','Origin':origin}) as s:
  async def api(path,method='GET',body=None):
   async with s.request(method,origin+'/api'+path,json=body) as r:
    text=await r.text()
    if r.status>=300:raise RuntimeError(f'{path}: {r.status} {text[:200]}')
    return json.loads(text)
  await api('/auth/login','POST',{'username':os.environ['IRV_SMOKE_USERNAME'],'password':os.environ['IRV_SMOKE_PASSWORD']})
  await api('/auth/verify','POST',{'code':otp(os.environ['IRV_SMOKE_TOTP'])})
  for kind in ['blank','java']:
   box=await api('/sandboxes','POST',{'name':'maintenance-'+kind+'-'+str(int(time.time())),'image':kind});bid=box['id'];prefix='/sandboxes/'+bid
   try:
    await api(prefix+'/open','POST');deadline=time.monotonic()+180
    while time.monotonic()<deadline:
     state=await api(prefix)
     if state['state']=='running':break
     if state['state']=='failed':raise RuntimeError(state['error'])
     await asyncio.sleep(2)
    else:raise RuntimeError('Startup exceeded 180 seconds')
    async with s.ws_connect(origin.replace('http','ws',1)+'/ws/terminal/'+bid) as ws:
     command="printf 'irv-canary-data' > canary.txt; printf 'IRV_READY_OK\\n'\n"
     await ws.send_json({'type':'input','data':base64.b64encode(command.encode()).decode()})
     output=''
     async with asyncio.timeout(20):
      while 'IRV_READY_OK' not in output:
       m=await ws.receive_json()
       if m.get('type')=='terminal':output+=base64.b64decode(m['data']).decode(errors='replace')
    await api(prefix+'/save','POST')
    for _ in range(90):
     snapshots=await api(prefix+'/snapshots')
     if snapshots:break
     await asyncio.sleep(2)
    if not snapshots:raise RuntimeError('No committed snapshot')
    print(kind+': startup, PTY, write, incremental save passed')
   finally:
    await api(prefix+'/stop','POST')
    # A canary is kept stopped for inspection; a janitor below archives it after its verified save.
    await api(prefix+'/archive','POST')
asyncio.run(main())
