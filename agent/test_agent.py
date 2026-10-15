import unittest,tempfile,pathlib,os,hashlib,asyncio
os.environ['IRV_ALLOW_PROCESS_CONTROL']='0'
os.environ['IRV_DEV_UID']=str(os.getuid())
from agent import Agent,safe_path,validate_config,CHUNK
CONFIG='schema_version=1\nfolder_name="workspace"\nentrypoint="scripts/environment.sh"\n'
class Paths(unittest.TestCase):
 def test_traversal_and_outside_symlink(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'escape').symlink_to('/etc')
   for name in ['../passwd','/etc/passwd','escape/passwd']:
    with self.assertRaises(ValueError):safe_path(p,name)
 def test_keyword_shell_injection_rejected(self):
  with self.assertRaises(ValueError):validate_config(CONFIG+'[keywords."bad; touch /tmp/bad"]\nroot="workspace"\npath="."')
 def test_no_provider_config(self):
  with self.assertRaises(ValueError):validate_config(CONFIG+'[fly]\ncpus=100')
class Snapshots(unittest.IsolatedAsyncioTestCase):
 async def test_incremental_dedup_and_restore(self):
  with tempfile.TemporaryDirectory() as d:
   a=Agent();a.root=pathlib.Path(d)/'source';a.root.mkdir();a.log=lambda *x:None
   blobs={};manifest={};puts=[]
   async def req(method,path,**kw):
    nonlocal manifest
    if path=='/chunks/missing':return [h for h in kw['json'] if h not in blobs]
    if method=='PUT':blobs[path.split('/')[-1]]=kw['data'];puts.append(path);return {}
    if method=='GET':return blobs[path.split('/')[-1]]
    if path=='/snapshots':manifest=kw['json'];return {'snapshot':'test'}
    return {}
   a.request=req
   (a.root/'large').write_bytes(b'a'*CHUNK+b'b'*123)
   (a.root/'empty').mkdir();(a.root/'relative').symlink_to('large')
   await a.snapshot();self.assertEqual(len(puts),2)
   await a.snapshot();self.assertEqual(len(puts),2)
   with (a.root/'large').open('r+b') as f:f.seek(CHUNK);f.write(b'c')
   await a.snapshot();self.assertEqual(len(puts),3)
   target=Agent();target.root=pathlib.Path(d)/'restore';target.root.mkdir();target.request=req;target.log=lambda *x:None
   await target.restore(manifest)
   self.assertEqual((target.root/'large').read_bytes(),(a.root/'large').read_bytes())
   self.assertTrue((target.root/'relative').is_symlink());self.assertTrue((target.root/'empty').is_dir())
 async def test_failed_upload_does_not_commit(self):
  with tempfile.TemporaryDirectory() as d:
   a=Agent();a.root=pathlib.Path(d);(a.root/'file').write_text('unsaved');commits=[]
   async def req(method,path,**kw):
    if path=='/chunks/missing':return kw['json']
    if method=='PUT':raise RuntimeError('storage unavailable')
    if path=='/snapshots':commits.append(1)
   a.request=req
   with self.assertRaises(RuntimeError):await a.snapshot()
   self.assertEqual(commits,[])

class FinalCheckpoint(unittest.IsolatedAsyncioTestCase):
 async def test_final_save_syncs_config_and_flushes_logs_before_ack(self):
  from unittest.mock import patch,AsyncMock
  with tempfile.TemporaryDirectory() as d, patch('agent.RUNTIME',pathlib.Path(d)/'runtime'):
   a=Agent();a.root=pathlib.Path(d)/'workspace';a.root.mkdir();a.ready=True
   (a.root/'config.toml').write_text(CONFIG)
   (a.root/'scripts').mkdir();(a.root/'scripts/environment.sh').write_text('export TEST=ok\n')
   a.files={'scripts/environment.sh':''};calls=[]
   async def sync():calls.append('/config-sync')
   a.sync_config=sync
   a.terminate_work=AsyncMock()
   async def req(method,path,**kw):
    calls.append(path)
    if path=='/events':return {'ack':[e['id'] for e in kw['json']]}
    if path=='/chunks/missing':return kw['json']
    if path=='/snapshots':return {'snapshot':'committed'}
    return {}
   a.request=req;a.log('process:test','last buffered log')
   result=await a.snapshot(final=True,shutdown=False)
   self.assertTrue(result['verified'] and result['logs_flushed'])
   self.assertLess(calls.index('/config-sync'),calls.index('/snapshots'))
   self.assertLess(calls.index('/snapshots'),calls.index('/events'))
   self.assertFalse(a.events);self.assertTrue(a.quiesced);self.assertFalse(a.ready)
   a.terminate_work.assert_not_awaited()

 async def test_failed_log_flush_cannot_acknowledge_final_checkpoint(self):
  from unittest.mock import patch,AsyncMock
  with tempfile.TemporaryDirectory() as d, patch('agent.RUNTIME',pathlib.Path(d)/'runtime'):
   a=Agent();a.root=pathlib.Path(d)/'workspace';a.root.mkdir();a.ready=True
   a.sync_config=AsyncMock();a.unfreeze=unittest.mock.Mock()
   async def req(method,path,**kw):
    if path=='/snapshots':return {'snapshot':'committed'}
    if path=='/events':raise RuntimeError('logs unavailable')
    return {}
   a.request=req;a.log('process:test','keep this log')
   with self.assertRaises(RuntimeError):await a.snapshot(final=True)
   self.assertTrue(a.events);self.assertFalse(a.quiesced);self.assertTrue(a.ready)
   a.unfreeze.assert_called_once()

 async def test_concurrent_log_flush_does_not_drop_or_duplicate_batches(self):
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as d, patch('agent.RUNTIME',pathlib.Path(d)/'runtime'):
   a=Agent();a.events=[{'id':str(i)} for i in range(250)];received=[]
   async def req(method,path,**kw):
    await asyncio.sleep(0);received.extend(e['id'] for e in kw['json']);return {'ack':[e['id'] for e in kw['json']]}
   a.request=req
   await asyncio.gather(a.flush_events(),a.flush_events())
   self.assertEqual(received,[str(i) for i in range(250)]);self.assertFalse(a.events)

if __name__=='__main__':unittest.main()
