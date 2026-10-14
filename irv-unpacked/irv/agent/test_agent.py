import unittest,tempfile,pathlib,os,hashlib,asyncio
os.environ['IRV_ALLOW_PROCESS_CONTROL']='0'
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
if __name__=='__main__':unittest.main()
