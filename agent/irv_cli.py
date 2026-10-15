#!/usr/bin/env python3
"""Guarded startup-file editing and session logs. No provider credential in this process."""
import argparse,asyncio,contextlib,json,os,pathlib,shlex,subprocess,sys,tempfile,time,uuid
import aiohttp,tomllib
SOCKET=os.environ.get('IRV_EDITOR_SOCKET','/persist/.irv-runtime/editor.sock')
async def main(connector_factory=None,origin='http://irv'):
    parser=argparse.ArgumentParser(prog='irv');sub=parser.add_subparsers(dest='cmd',required=True)
    p=sub.add_parser('edit');p.add_argument('path',nargs='?',default='config.toml');p.add_argument('--takeover',action='store_true');p.add_argument('--recover',metavar='EDITOR_ID');p.add_argument('--delete',action='append',default=[],metavar='HELPER');p.add_argument('--review-latest',action='store_true',help='Use current revision after reviewing a recovered draft against irv status')
    p=sub.add_parser('logs');p.add_argument('--session',default='');p.add_argument('--follow',action='store_true');p.add_argument('--local',action='store_true')
    sub.add_parser('status');args=parser.parse_args()
    if args.cmd=='logs' and args.local:
        path=pathlib.Path(SOCKET).parent/'logs/events.jsonl'
        if path.exists():print(path.read_text(),end='')
        return
    async with aiohttp.ClientSession(connector=connector_factory() if connector_factory else aiohttp.UnixConnector(path=SOCKET),timeout=aiohttp.ClientTimeout(total=60)) as client:
        async def call(op,body=None):
            async with client.request('GET' if body is None else 'POST',origin+'/'+op,json=body) as r:
                data=await r.json()
                if r.status>=300:raise RuntimeError(data.get('error',str(data)))
                return data
        if args.cmd=='status':print(json.dumps(await call('status'),indent=2));return
        if args.cmd=='logs':
            after=0
            while True:
                from urllib.parse import urlencode
                rows=await call('logs?'+urlencode({'after':after,'session':args.session}))
                for e in rows:print(f"{e['created']} [{e['source']}/{e.get('stream','system')}] {e['message']}",flush=True);after=e['id']
                if not args.follow and len(rows)<500:return
                if len(rows)<500:await asyncio.sleep(2)
        editor=uuid.uuid4().hex;lease=await call('acquire',{'editor':editor,'takeover':args.takeover})
        auth={'editor':editor,'fence':lease['fence'],'lease_token':lease['lease_token']};revision=lease['revision'];version=0;active=True
        data={'config':lease['config'],'files':lease['files'],'delete':[]}
        if args.recover:
            recovered=await call('recover',{**auth,'draft_editor':args.recover});data=json.loads(recovered['bundle'])
            print('Recovered draft. Review against current revision before saving.');revision=lease['revision'] if args.review_latest else recovered['base_revision']
        drafts=pathlib.Path(os.environ.get('WORKSPACE_DIR',str(pathlib.Path.home()/'workspace')))/'.irv-drafts'/editor;drafts.mkdir(parents=True,mode=0o700)
        draft=drafts/'bundle.json';draft.write_text(json.dumps(data,indent=2));os.chmod(draft,0o600)
        # Editing the complete bundle permits multi-file changes with one validation boundary.
        filesdir=drafts/'files';filesdir.mkdir();paths={'config.toml':data['config'],**data['files']}
        for name,text in paths.items():
            target=filesdir/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
        target=filesdir/args.path
        if args.path not in paths:raise RuntimeError('Register helper paths via config.toml helpers and create them in the draft directory first')
        print('Editing lease acquired. Related draft files: '+str(filesdir))
        print('Actual managed files stay read-only. Saved drafts are retained at '+str(drafts))
        def bundle():
            config=(filesdir/'config.toml').read_text();files={}
            try:
                parsed=tomllib.loads(config);names={parsed['entrypoint'],*parsed.get('helpers',[])}
            except Exception:
                names={str(p.relative_to(filesdir)) for p in filesdir.rglob('*') if p.is_file() and not p.name.startswith('.') and not p.name.endswith('~') and p!=filesdir/'config.toml'}
            for name in names:
                p=filesdir/name
                if p.resolve().is_relative_to(filesdir.resolve()) and p.is_file() and not p.is_symlink() and p.stat().st_size<=65536:files[name]=p.read_text()
            return {'config':config,'files':files,'delete':args.delete}
        async def save(commit=False):
            nonlocal version,revision,data
            data=bundle();draft.write_text(json.dumps(data,indent=2));version+=1
            body={**auth,**data,'revision':revision,'draft_version':version}
            if commit:body['request_id']=uuid.uuid4().hex
            try:result=await call('commit' if commit else 'draft',body)
            except (aiohttp.ClientError,asyncio.TimeoutError):
                if not commit:raise
                result=await call('commit',body) # same request ID after a lost response
            if result.get('committed'):revision=result['revision'];args.delete=[]
            if result.get('error'):print(result['error'],file=sys.stderr)
            return result
        stopping=asyncio.Event()
        async def monitor():
            nonlocal active
            last=json.dumps(bundle(),sort_keys=True);renew=0
            while not stopping.is_set():
                try:await asyncio.wait_for(stopping.wait(),1);break
                except asyncio.TimeoutError:pass
                try:
                    if time.monotonic()-renew>20:await call('renew',auth);renew=time.monotonic()
                    current=json.dumps(bundle(),sort_keys=True)
                    if current!=last:await save(True);last=current
                except Exception as e:
                    active=False;print('Publishing paused: '+str(e)+'; local draft remains available.',file=sys.stderr);return
        task=asyncio.create_task(monitor())
        try:
            proc=await asyncio.create_subprocess_exec(*shlex.split(os.environ.get('EDITOR','nano')),str(target),cwd=filesdir)
            await proc.wait();stopping.set()
            await task
            if active:
                result=await save(True)
                if result.get('committed'):print('Saved revision '+str(result['revision'])+'; awaiting machine acknowledgement.')
                else:print('Draft saved; active configuration unchanged. Recover using: irv edit --recover '+editor)
            else:print('Local draft: '+str(drafts))
        finally:
            task.cancel()
            with contextlib.suppress(Exception):await call('release',auth)
if __name__=='__main__':
    try:asyncio.run(main())
    except (Exception,KeyboardInterrupt) as e:print(str(e),file=sys.stderr);sys.exit(1)
