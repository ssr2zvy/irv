import unittest,tempfile,pathlib,os,json,subprocess,asyncio,hashlib
from unittest.mock import patch
from agent import Agent,atomic
from managed import ManagedFiles
CONF='schema_version=1\nfolder_name="workspace"\nentrypoint="scripts/start.sh"\nhelpers=[]\n'
@unittest.skipUnless(os.geteuid()==0,'Privileged file-guard tests require root; run in sandbox image')
class Managed(unittest.TestCase):
 def test_guard_blocks_write_delete_rename_and_parent_replacement(self):
  try:subprocess.run(['/bin/true'],user=1000,group=1000,extra_groups=[],check=True)
  except (OSError,subprocess.CalledProcessError):self.skipTest('Host has no SETUID/SETGID capability; run this test in the supplied sandbox Docker image')
  with tempfile.TemporaryDirectory() as d:
   base=pathlib.Path(d);os.chmod(base,0o755);root=base/'workspace';state=base/'private'
   guard=ManagedFiles(root,state);guard.apply({'config':CONF,'files':{'scripts/start.sh':'echo yes'},'revision':1},atomic)
   actions=["(p/'config.toml').write_text('bad')","(p/'config.toml').unlink()","(p/'scripts/start.sh').rename(p/'scripts/new.sh')","(p/'scripts').rename(p/'moved')","(p/'config.toml').chmod(0o666)"]
   for action in actions:
    code='import pathlib\np=pathlib.Path('+repr(str(root))+')\n'+action
    result=subprocess.run(['python3','-c',code],user=1000,group=1000,extra_groups=[],capture_output=True)
    self.assertNotEqual(result.returncode,0,action)
   result=subprocess.run(['python3','-c',"from pathlib import Path;Path("+repr(str(root/'project.txt'))+").write_text('normal')"],user=1000,group=1000,extra_groups=[],capture_output=True)
   self.assertEqual(result.returncode,0,result.stderr)
 def test_unmanage_keeps_content_delete_removes_and_old_fd_cannot_write_new_file(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);g=ManagedFiles(p/'workspace',p/'private',uid=os.getuid());g.apply({'config':CONF,'files':{'scripts/start.sh':'a','scripts/helper.sh':'keep'},'revision':1},atomic)
   os.chmod(p/'workspace/scripts/start.sh',0o644)
   fd=os.open(p/'workspace/scripts/start.sh',os.O_WRONLY)
   g.apply({'config':CONF,'files':{'scripts/start.sh':'new'},'revision':2},atomic)
   os.write(fd,b'old');os.close(fd)
   self.assertEqual((p/'workspace/scripts/start.sh').read_text(),'new')
   self.assertEqual((p/'workspace/scripts/helper.sh').read_text(),'keep');self.assertEqual((p/'workspace/scripts/helper.sh').stat().st_uid,os.getuid())
   g.apply({'config':CONF,'files':{'scripts/start.sh':'new'},'delete':['scripts/helper.sh'],'revision':3},atomic)
   self.assertFalse((p/'workspace/scripts/helper.sh').exists())
 def test_symlink_managed_parent_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);root=p/'workspace';root.mkdir();(root/'scripts').symlink_to('/tmp')
   with self.assertRaises(ValueError):ManagedFiles(root,p/'private').apply({'config':CONF,'files':{'scripts/start.sh':'a'},'revision':1},atomic)

class Replay(unittest.IsolatedAsyncioTestCase):
 async def test_lost_log_response_replays_identical_events_after_agent_restart(self):
  with tempfile.TemporaryDirectory() as d,patch('agent.RUNTIME',pathlib.Path(d)):
   a=Agent();a.session='old-session';a.log('process:x','hello','stderr');event=dict(a.events[0])
   async def lost(*args,**kw):raise OSError('response lost')
   a.request=lost
   with self.assertRaises(OSError):await a.flush_events()
   b=Agent();self.assertEqual(b.events,[event]);seen=[]
   async def ack(*args,**kw):seen.extend(kw['json']);return {'ack':[e['id'] for e in kw['json']]}
   b.request=ack;await b.flush_events();self.assertEqual(seen,[event]);self.assertFalse(b.events)
   self.assertIn('hello',(pathlib.Path(d)/'logs/events.jsonl').read_text())
 async def test_missing_or_partial_ack_does_not_remove_events(self):
  with tempfile.TemporaryDirectory() as d,patch('agent.RUNTIME',pathlib.Path(d)):
   a=Agent();a.log('agent','retain')
   async def missing(*args,**kw):return {'ack':[]}
   a.request=missing
   with self.assertRaises(ValueError):await a.flush_events()
   self.assertEqual(len(a.events),1)
 @unittest.skipUnless(os.geteuid()==0,'Managed file application requires root')
 async def test_config_lost_ack_retries_without_reapplying_files(self):
  with tempfile.TemporaryDirectory() as d,patch('agent.RUNTIME',pathlib.Path(d)/'runtime'):
   a=Agent();a.root=pathlib.Path(d)/'workspace';a.root.mkdir();attempts=0;applies=[]
   bundle={'config':CONF,'files':{'scripts/start.sh':'echo yes'},'revision':1};version={'bundle':json.dumps(bundle),'content_hash':'hash'}
   real=a.overlay
   def overlay(data):applies.append(data['revision']);real(data)
   a.overlay=overlay
   async def req(method,path,**kw):
    nonlocal attempts
    if path.startswith('/config/changes'):return {'latest':1,'versions':[version] if path.endswith('=0') else []}
    attempts+=1
    if attempts==1:raise OSError('ack response lost')
    return {'ok':True}
   a.request=req
   with self.assertRaises(OSError):await a.sync_config()
   await a.sync_config();self.assertEqual(applies,[1]);self.assertEqual(a.last_ack,1)
 async def test_stdout_and_stderr_are_distinct(self):
  with tempfile.TemporaryDirectory() as d,patch('agent.RUNTIME',pathlib.Path(d)):
   a=Agent();p=await asyncio.create_subprocess_exec('/bin/sh','-c','echo out; echo err >&2',stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
   await asyncio.gather(a.pipe_log(p.stdout,'process:test','stdout'),a.pipe_log(p.stderr,'process:test','stderr'));await p.wait()
   self.assertEqual({e['stream'] for e in a.events},{'stdout','stderr'})
if __name__=='__main__':unittest.main()

class BrokerSocket(unittest.IsolatedAsyncioTestCase):
 async def test_unix_broker_accepts_dev_and_rejects_unrelated_uid(self):
  import socket
  try:
   probe=socket.socket(socket.AF_UNIX);probe.close()
   subprocess.run(['/bin/true'],user=1000,group=1000,extra_groups=[],check=True)
  except (OSError,subprocess.CalledProcessError):self.skipTest('Host lacks Unix socket/UID support; run in sandbox Docker image')
  with tempfile.TemporaryDirectory() as d,patch('agent.RUNTIME',pathlib.Path(d)),patch.dict(os.environ,{'IRV_DEV_UID':'1000'}):
   os.chmod(d,0o755);a=Agent()
   async def request(*args,**kwargs):return {'revision':7}
   a.request=request;await a.start_local_api()
   code="import asyncio,aiohttp\nasync def main():\n async with aiohttp.ClientSession(connector=aiohttp.UnixConnector(path="+repr(d+'/editor.sock')+")) as c:\n  async with c.get('http://irv/status') as r:assert (await r.json())['revision']==7\nasyncio.run(main())"
   try:
    p=await asyncio.create_subprocess_exec('python3','-c',code,user=1000,group=1000,extra_groups=[],stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
    _,err=await p.communicate();self.assertEqual(p.returncode,0,err)
    p=await asyncio.create_subprocess_exec('python3','-c',code,user=1001,group=1001,extra_groups=[],stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
    await p.communicate();self.assertNotEqual(p.returncode,0)
   finally:await a.local_runner.cleanup()
