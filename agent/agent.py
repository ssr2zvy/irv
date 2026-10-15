#!/usr/bin/env python3
"""irv sandbox supervisor. Privileged file broker; all development programs run as the unprivileged dev user."""
import asyncio, codecs, base64, contextlib, fcntl, hashlib, json, os, pathlib, pty, re, shlex
import signal, traceback, struct, subprocess, sys, termios, time, tomllib, uuid
import aiohttp
from aiohttp import web
from managed import ManagedFiles

CHUNK = 4 * 1024 * 1024
HOME_DIR = pathlib.Path(os.environ.get('IRV_HOME', '/home/dev'))
BASE = pathlib.Path(os.environ.get('IRV_WORKSPACE_BASE', '/home/dev'))
RUNTIME = pathlib.Path(os.environ.get('IRV_RUNTIME', '/tmp/irv-runtime'))
API = os.environ.get('IRV_API', 'http://localhost:8080').rstrip('/')
TOKEN = os.environ.get('IRV_AGENT_TOKEN', '')
RESERVED = {'cd','exit','exec','source','eval','bash','sh','sudo','export','unset','command','builtin'}


def safe_path(root, name):
    p = pathlib.PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or '\\' in name or '\x00' in name:
        raise ValueError('Path escapes designated workspace')
    out = root.joinpath(*p.parts)
    if not out.resolve().is_relative_to(root.resolve()):
        raise ValueError('Symlink escapes designated workspace')
    return out


def validate_config(text):
    c = tomllib.loads(text)
    if set(c) - {'schema_version','folder_name','entrypoint','helpers','preview_ports','keywords','processes'}:
        raise ValueError('Unsupported configuration key')
    if c.get('schema_version') != 1 or not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_-]{0,39}', c.get('folder_name','')):
        raise ValueError('Invalid schema or workspace folder')
    if pathlib.PurePosixPath(c['entrypoint']).is_absolute() or '..' in pathlib.PurePosixPath(c['entrypoint']).parts:
        raise ValueError('Entrypoint must be inside workspace')
    for name, k in c.get('keywords',{}).items():
        if not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]{0,31}', name) or name in RESERVED:
            raise ValueError('Invalid keyword')
        if k.get('root') not in ('workspace','image'):
            raise ValueError('Invalid keyword root')
    return c


def atomic(path, data, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name('.irv-' + uuid.uuid4().hex)
    with open(tmp, 'wb') as f:
        f.write(data if isinstance(data, bytes) else data.encode())
        f.flush(); os.fsync(f.fileno())
    os.chmod(tmp, mode); os.replace(tmp, path)
    parent=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(parent)
    finally:os.close(parent)


class Agent:
    def __init__(self):
        self.session = None; self.http = None; self.ws = None; self.root = None; self.config = {}; self.revision = 0
        self.files = {}; self.baseline = None; self.terminals = {}; self.processes = {}
        self.frozen = []; self.env = {}; self.ready = False; self.events = []
        self.serial = asyncio.Lock(); self.event_lock = asyncio.Lock(); self.quiesced = False; self.tasks = set(); self.preview_ws = {}; self.pending_preview = {}
        if RUNTIME.is_symlink():raise ValueError('Runtime directory cannot be a symlink')
        RUNTIME.mkdir(parents=True, exist_ok=True)
        self.private=RUNTIME/'private';self.private.mkdir(exist_ok=True);os.chmod(self.private,0o700)
        if self.private.is_symlink():raise ValueError('Private runtime cannot be a symlink')
        if os.geteuid()==0:os.chown(self.private,0,0)
        self.logfile = self.private/'events.jsonl'
        if self.logfile.is_symlink():raise ValueError('Log spool cannot be a symlink')
        self.archive=RUNTIME/'logs';self.archive.mkdir(exist_ok=True);os.chmod(self.archive,0o755)
        self.producer=uuid.uuid4().hex;self.sequence=0;self.managed=None;self.local_edits={}
        self.last_ack=0
        if self.logfile.exists():
            for line in self.logfile.read_text().splitlines():
                with contextlib.suppress(Exception): self.events.append(json.loads(line))

    def task(self, coro):
        t = asyncio.create_task(coro); self.tasks.add(t); t.add_done_callback(self.tasks.discard); return t

    def log(self, source, message, stream='system'):
        self.sequence+=1
        e=dict(id=uuid.uuid4().hex,source=source,message=str(message)[:16000],session=self.session,
               timestamp=int(time.time()*1000),stream=stream,producer=self.producer,sequence=self.sequence)
        line=json.dumps(e)+'\n'
        with self.logfile.open('a') as f:f.write(line);f.flush();os.fsync(f.fileno())
        self.events.append(e)
        archive=self.archive/'events.jsonl'
        if archive.exists() and archive.stat().st_size>8*1024*1024:
            for i in range(3,0,-1):
                old=self.archive/f'events.{i}.jsonl'
                if old.exists():old.replace(self.archive/f'events.{i+1}.jsonl')
            archive.replace(self.archive/'events.1.jsonl')
        with archive.open('a') as f:f.write(line)
        os.chmod(archive,0o644)
        print(f'[{source}/{stream}] {str(message)[:16000]}',flush=True)

    async def request(self, method, path, **kw):
        async with self.http.request(method, API+'/agent'+path, **kw) as res:
            if res.status >= 300: raise RuntimeError(f'{method} {path}: {res.status} {(await res.text())[:300]}')
            if path.startswith('/chunks/') and method == 'GET': return await res.read()
            return await res.json()

    async def emit(self, data):
        if self.ws and not self.ws.closed: await self.ws.send_json(data)

    def bundle(self):
        text=(self.root/'config.toml').read_text()
        return {'config':text,'files':{n:safe_path(self.root,n).read_text() for n in self.files}}

    def child_args(self):
        return dict(user=int(os.environ.get('IRV_DEV_UID','1000')),group=int(os.environ.get('IRV_DEV_UID','1000')),extra_groups=[])

    async def local_api(self,req):
        try:
            op=req.match_info['op'];body=await req.json() if req.method=='POST' else {}
            if op=='status':return web.json_response(await self.request('GET','/sync'))
            if op=='logs':return web.json_response(await self.request('GET','/logs?'+req.query_string))
            if op not in ('acquire','renew','release','recover','draft','commit'):raise ValueError('Unsupported editor operation')
            if op=='acquire':
                async with self.serial:
                    await self.sync_config()
                    result=await self.request('POST','/sync/acquire',json=body)
                    # Acquisition is serialized with DB commits. Ensure that exact revision is applied.
                    await self.sync_config()
                    if result['revision']!=self.revision:raise ValueError('Configuration changed during handoff; retry')
            else:result=await self.request('POST','/sync/'+op,json=body)
            return web.json_response(result)
        except Exception as e:return web.json_response({'error':str(e)},status=409)

    async def start_local_api(self):
        socket=RUNTIME/'editor.sock';socket.unlink(missing_ok=True)
        app=web.Application(client_max_size=3*1024*1024);app.router.add_route('*','/{op}',self.local_api)
        self.local_runner=web.AppRunner(app);await self.local_runner.setup()
        await web.UnixSite(self.local_runner,str(socket)).start();os.chown(socket,0,int(os.environ.get('IRV_DEV_UID','1000')));os.chmod(socket,0o660)

    def keywords(self):
        lines = ['for _irv_k in "${IRV_KEYWORD_NAMES[@]}"; do unset -f "$_irv_k"; done', 'IRV_KEYWORD_NAMES=()']
        for name, spec in self.config.get('keywords',{}).items():
            path = str(safe_path(self.root,spec['path'])) if spec['root']=='workspace' else spec['path']
            if not path.startswith('/') or '\n' in path: raise ValueError('Invalid keyword path')
            lines.append('IRV_KEYWORD_NAMES+=('+shlex.quote(name)+')')
            lines.append(f'{name}() {{ builtin cd -- {shlex.quote(path)}; }}')
        atomic(RUNTIME/'keywords.sh', '\n'.join(lines)+'\n',0o644)
        source='source '+shlex.quote(str(RUNTIME/'keywords.sh'))
        atomic(RUNTIME/'bashrc', "[ -f ~/.bashrc ] && source ~/.bashrc\nexport PROMPT_COMMAND="+shlex.quote(source)+"\n"+source+"\n",0o644)

    def overlay(self,data,force=False):
        c=validate_config(data['config'])
        if c['folder_name']!=self.root.name:raise ValueError('Folder name cannot change after first start')
        if self.managed is None:self.managed=ManagedFiles(self.root,self.private/'managed',int(os.environ.get('IRV_DEV_UID','1000')))
        already_frozen=bool(self.frozen) or self.quiesced
        self.freeze()
        try:
            self.managed.apply(data,atomic)
            self.files=data['files'];self.config=c;self.revision=data['revision'];self.baseline=self.bundle();self.keywords()
        finally:
            if not already_frozen:self.unfreeze()

    async def restore(self, manifest):
        if not manifest or not manifest.get('entries'): return
        start=time.monotonic(); self.log('restore','Restoring designated workspace')
                # Rootfs/volume may already have this working copy; never overwrite it on a stop/start.
        if (self.root/'config.toml').exists(): return
        for entry in sorted(manifest['entries'],key=lambda e:len(pathlib.PurePosixPath(e['path']).parts)):
            if entry['type']=='link': continue
            p=safe_path(self.root,entry['path'])
            if entry['type']=='dir': p.mkdir(parents=True,exist_ok=True); continue
            p.parent.mkdir(parents=True,exist_ok=True)
            tmp=p.with_name('.irv-restore-'+uuid.uuid4().hex)
            with tmp.open('wb') as f:
                for digest in entry['chunks']:
                    chunk=await self.request('GET','/chunks/'+digest)
                    if hashlib.sha256(chunk).hexdigest()!=digest: raise ValueError('Corrupt backup chunk')
                    f.write(chunk)
                f.flush();os.fsync(f.fileno())
            if tmp.stat().st_size!=entry['size']: raise ValueError('Corrupt backup size')
            os.chmod(tmp,entry.get('mode',0o644)&0o777);os.replace(tmp,p)
        # Symlinks are recreated last; never traverse one while restoring another entry.
        for entry in manifest['entries']:
            if entry['type']=='link':
                p=safe_path(self.root,entry['path']);p.parent.mkdir(parents=True,exist_ok=True);p.symlink_to(entry['target'])
        for entry in reversed(manifest['entries']):
            p=self.root/entry['path']
            if not p.is_symlink():
                with contextlib.suppress(OSError): os.chmod(p,entry.get('mode',0o755)&0o777);os.utime(p,ns=(entry.get('mtime_ns',time.time_ns()),)*2)
        for entry in manifest['entries']:
            target=self.root/entry['path'];uid=int(os.environ.get('IRV_DEV_UID','1000'))
            if target.lstat().st_uid!=uid:os.chown(target,uid,uid,follow_symlinks=False)
        await self.request('POST','/telemetry',json={'kind':'restore','seconds':time.monotonic()-start,'bytes':sum(e.get('size',0) for e in manifest['entries'])})

    def user_pids(self):
        result=[]
        for p in pathlib.Path('/proc').iterdir():
            if not p.name.isdigit() or int(p.name)==os.getpid(): continue
            try:
                if p.stat().st_uid==int(os.environ.get('IRV_DEV_UID','1000')) and int(p.name)>1:result.append(int(p.name))
            except OSError: pass
        return result

    def freeze(self):
        if os.environ.get('IRV_ALLOW_PROCESS_CONTROL','1')!='1':return
        for _ in range(2):
            for pid in self.user_pids():
                if pid not in self.frozen:
                    with contextlib.suppress(ProcessLookupError,PermissionError):os.kill(pid,signal.SIGSTOP);self.frozen.append(pid)

    def unfreeze(self):
        for pid in self.frozen:
            with contextlib.suppress(ProcessLookupError,PermissionError):os.kill(pid,signal.SIGCONT)
        self.frozen=[]

    async def terminate_work(self):
        self.unfreeze()
        pids=self.user_pids() if os.environ.get('IRV_ALLOW_PROCESS_CONTROL','1')=='1' else []
        for pid in pids:
            with contextlib.suppress(ProcessLookupError,PermissionError):os.kill(pid,signal.SIGTERM)
        end=time.monotonic()+10
        while time.monotonic()<end and any(pathlib.Path('/proc',str(p)).exists() for p in pids):await asyncio.sleep(.2)
        for pid in pids:
            with contextlib.suppress(ProcessLookupError,PermissionError):os.kill(pid,signal.SIGKILL)

    async def flush_events(self):
        # The periodic uploader and final save share this lock; never drop another
        # uploader's batch when both finish at once. Backend event IDs are idempotent.
        async with self.event_lock:
            while self.events:
                batch=self.events[:100]
                reply=await self.request('POST','/events',json=batch)
                if reply.get('ack')!=[e['id'] for e in batch]:raise ValueError('Missing log acknowledgement; keeping replay queue')
                del self.events[:len(batch)]
                atomic(self.logfile,''.join(json.dumps(e)+'\n' for e in self.events))

    async def sync_config(self):
        if self.managed:self.managed.verify()
        while True:
            pending=await self.request('GET','/config/changes?after='+str(self.revision))
            for v in pending['versions']:
                data=json.loads(v['bundle'])
                if validate_config(data['config'])['folder_name']!=self.root.name:
                    self.revision=data['revision'];continue
                self.overlay(data)
                await self.request('POST','/config/ack',json={'revision':self.revision,'hash':v['content_hash']})
                self.last_ack=self.revision
            if self.revision>=pending['latest']:break
            if not pending['versions']:raise ValueError('Missing configuration revision')
        # Re-ACK after a lost response without repeating the file operation.
        if self.last_ack!=self.revision and self.revision:
            pending=await self.request('GET','/config/changes?after='+str(self.revision-1))
            for v in pending['versions']:
                if json.loads(v['bundle'])['revision']==self.revision:
                    await self.request('POST','/config/ack',json={'revision':self.revision,'hash':v['content_hash']});self.last_ack=self.revision

    async def snapshot(self, final=False, shutdown=False):
        start=time.monotonic();was_ready=self.ready
        if self.root.is_symlink():raise ValueError('Workspace root cannot be replaced by a symlink')
        if final:self.quiesced=True;self.ready=False
        if shutdown:await self.terminate_work()
        self.freeze()
        try:
            if final:await self.sync_config()
            entries=[];total=0;uploaded=0
            for base,dirs,files in os.walk(self.root,followlinks=False):
                dirs.sort();files.sort()
                for name in dirs+files:
                    p=pathlib.Path(base)/name;st=p.lstat();rel=str(p.relative_to(self.root))
                    e={'path':rel,'mode':st.st_mode&0o777,'mtime_ns':st.st_mtime_ns}
                    if p.is_symlink():e.update(type='link',target=os.readlink(p))
                    elif p.is_dir():e.update(type='dir')
                    elif p.is_file():
                        hashes=[]
                        with p.open('rb') as f:
                            while chunk:=f.read(CHUNK):
                                digest=hashlib.sha256(chunk).hexdigest();hashes.append(digest)
                                missing=await self.request('POST','/chunks/missing',json=[digest])
                                if missing:await self.request('PUT','/chunks/'+digest,data=chunk);uploaded+=len(chunk)
                        e.update(type='file',size=st.st_size,chunks=hashes);total+=st.st_size
                    else:continue # sockets, FIFOs and device nodes are runtime-only
                    entries.append(e)
                    if len(entries)>100000:raise ValueError('Workspace exceeds 100,000-entry limit')
            result=await self.request('POST','/snapshots',json={'version':1,'config_revision':self.revision,'entries':entries})
            self.log('save',f'Saved {total} bytes; uploaded {uploaded} new bytes; snapshot {result["snapshot"]}')
            await self.request('POST','/telemetry',json={'kind':'save','seconds':time.monotonic()-start,'bytes':uploaded})
            if final:await self.flush_events()
            return {'verified':True,'logs_flushed':final,**result}
        except BaseException:
            self.unfreeze();self.quiesced=False;self.ready=was_ready
            raise
        finally:
            if not final:self.unfreeze()

    async def pipe_log(self, reader, source, stream='stdout'):
        decoder=codecs.getincrementaldecoder('utf-8')('replace')
        while True:
            chunk=await reader.read(16000)
            text=decoder.decode(chunk,final=not chunk)
            if text:self.log(source,text,stream)
            if not chunk:break

    async def setup(self):
        start=time.monotonic();self.env={k:v for k,v in os.environ.items() if not k.startswith(('IRV_','AWS_','FLY_'))}
        self.env.update(HOME=str(HOME_DIR),USER='dev',WORKSPACE_DIR=str(self.root),TERM='xterm-256color')
        path=safe_path(self.root,self.config['entrypoint'])
        setupdir=RUNTIME/'setup';setupdir.mkdir(exist_ok=True);os.chown(setupdir,int(os.environ.get('IRV_DEV_UID','1000')),int(os.environ.get('IRV_DEV_UID','1000')));os.chmod(setupdir,0o700)
        out=setupdir/'environment.json';out.unlink(missing_ok=True)
        # Source entrypoint in a shell and capture exported variables from that same shell.
        code=f'set -e; source {shlex.quote(str(path))}; python3 -c '+shlex.quote('import os,json;open('+repr(str(out))+',"w").write(json.dumps(dict(os.environ)))')
        p=await asyncio.create_subprocess_exec('/bin/bash','-c',code,cwd=self.root,env=self.env,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,start_new_session=True,**self.child_args())
        log=self.task(self.pipe_log(p.stdout,'entrypoint'));err=self.task(self.pipe_log(p.stderr,'entrypoint','stderr'))
        try:await asyncio.wait_for(p.wait(),90)
        except asyncio.TimeoutError:os.killpg(p.pid,signal.SIGKILL);raise ValueError('Entrypoint exceeded 90-second setup budget')
        await asyncio.gather(log,err)
        if p.returncode!=0:raise ValueError(f'Entrypoint exited {p.returncode}')
        fd=os.open(out,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd) as f:self.env=json.loads(f.read(65537))
        if not isinstance(self.env,dict) or not all(isinstance(k,str) and isinstance(v,str) for k,v in self.env.items()):raise ValueError('Invalid setup environment')
        out.unlink();self.keywords()
        await self.request('POST','/telemetry',json={'kind':'setup','seconds':time.monotonic()-start})
        await self.start_processes()

    async def start_processes(self):
        for name,spec in self.config.get('processes',{}).items():
            if name in self.processes and self.processes[name].returncode is None:continue
            p=await asyncio.create_subprocess_exec('/bin/bash','-c',spec['command'],cwd=safe_path(self.root,spec.get('cwd','.')),env=self.env,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,start_new_session=True,**self.child_args())
            self.processes[name]=p;self.task(self.pipe_log(p.stdout,'process:'+name));self.task(self.pipe_log(p.stderr,'process:'+name,'stderr'));self.task(self.process_end(name,p))
            if spec.get('log_file'):self.task(self.tail_file(name,safe_path(self.root,spec['log_file']),p))

    async def process_end(self,name,p):
        code=await p.wait();self.log('process:'+name,f'Exited with code {code}')

    async def tail_file(self,name,path,p):
        offset=0
        while p.returncode is None:
            if path.is_file():
                if path.stat().st_size<offset:offset=0
                with path.open('r',errors='replace') as f:
                    f.seek(offset)
                    for _ in range(100):
                        line=f.readline(8000)
                        if not line:break
                        self.log('file:'+name,line.rstrip(),'file')
                    offset=f.tell()
            await asyncio.sleep(1)

    async def terminal(self, client):
        if not self.ready:raise ValueError('Environment is not ready')
        master,slave=pty.openpty();os.set_blocking(master,False)
        proc=subprocess.Popen(['/bin/bash','--rcfile',str(RUNTIME/'bashrc'),'-i'],cwd=self.root,env=self.env,stdin=slave,stdout=slave,stderr=slave,start_new_session=True,**self.child_args())
        os.close(slave);self.terminals[client]=(master,proc)
        loop=asyncio.get_running_loop()
        def read():
            try:
                data=os.read(master,16384)
                if not data:raise OSError()
                self.task(self.emit({'type':'terminal','client':client,'data':base64.b64encode(data).decode()}))
            except OSError:
                loop.remove_reader(master)
                with contextlib.suppress(OSError):os.close(master)
                self.terminals.pop(client,None);self.task(self.emit({'type':'terminal_exit','client':client}))
        loop.add_reader(master,read)

    async def http_proxy(self,data):
        port=int(data['port'])
        if port not in self.config.get('preview_ports',[]):raise ValueError('Undeclared preview port')
        # Fixed loopback destination; never let a supplied path become an absolute upstream URL.
        path=data['path']
        if not path.startswith('/') or path.startswith('//'):raise ValueError('Invalid request path')
        async with self.http.request(data['method'],f'http://127.0.0.1:{port}'+path,headers=data.get('headers',{}),data=base64.b64decode(data.get('body','')),allow_redirects=False) as r:
            body=await r.content.read(4*1024*1024+1)
            if len(body)>4*1024*1024:raise ValueError('Preview response exceeds 4 MiB')
            return {'status':r.status,'headers':dict(r.headers),'body':base64.b64encode(body).decode()}

    async def rpc(self,n):
        try:
            op=n['op'];data=n.get('data',{})
            if op=='http':result=await self.http_proxy(data)
            else:
                async with self.serial:
                    if op=='save':result=await self.snapshot(data.get('final',False),data.get('shutdown',False))
                    elif op=='apply':
                        self.ready=False
                        await self.sync_config();await self.terminate_work()
                        for process in self.processes.values():await process.wait()
                        await self.setup();self.ready=True;await self.emit({'type':'ready'});result={'ok':True}
                    elif op=='config':
                        await self.sync_config();result={'revision':self.revision}
                    elif op in ('resume','unfreeze'):
                        self.unfreeze();self.quiesced=False;boot=await self.request('GET','/bootstrap');self.session=boot.get('session');await self.sync_config()
                        if op=='resume':await self.setup()
                        else:await self.start_processes()
                        self.ready=True;await self.emit({'type':'ready'});result={'ok':True}
                    else:raise ValueError('Unknown operation')
            await self.emit({'type':'rpc_result','request':n['request'],'data':result})
        except Exception as e:
            self.log('agent',str(e));await self.emit({'type':'rpc_result','request':n['request'],'error':str(e)})

    async def dispatch(self,n):
        kind=n.get('type');client=n.get('client')
        if kind=='rpc':self.task(self.rpc(n))
        elif kind=='terminal_open':await self.terminal(client)
        elif kind=='terminal_close':
            if client in self.terminals:
                fd,p=self.terminals[client]
                with contextlib.suppress(ProcessLookupError):os.killpg(p.pid,signal.SIGHUP)
        elif kind=='input' and client in self.terminals:
            with contextlib.suppress(OSError):os.write(self.terminals[client][0],base64.b64decode(n['data']))
        elif kind=='resize' and client in self.terminals:
            fcntl.ioctl(self.terminals[client][0],termios.TIOCSWINSZ,struct.pack('HHHH',min(500,max(1,int(n['rows']))),min(500,max(1,int(n['cols']))),0,0))
        elif kind=='preview_open':
            self.pending_preview[client]=[];self.task(self.preview_open(n))
        elif kind=='preview_data' and client in self.preview_ws:
            ws=self.preview_ws[client]
            if n.get('binary'):await ws.send_bytes(base64.b64decode(n['data']))
            else:await ws.send_str(n['data'])
        elif kind=='preview_data' and client in self.pending_preview:
            if len(self.pending_preview[client])<20:self.pending_preview[client].append(n)
        elif kind=='preview_close':
            self.pending_preview.pop(client,None)
            if client in self.preview_ws:await self.preview_ws[client].close()

    async def preview_open(self,n):
        client=n['client'];port=int(n['port'])
        if port not in self.config.get('preview_ports',[]):return
        try:
            async with self.http.ws_connect(f'http://127.0.0.1:{port}'+n.get('path','/'),headers=n.get('headers',{}),protocols=n.get('protocols',[]),max_msg_size=4*1024*1024) as ws:
                if client not in self.pending_preview:return
                self.preview_ws[client]=ws
                for packet in self.pending_preview.pop(client,[]):
                    if packet.get('binary'):await ws.send_bytes(base64.b64decode(packet['data']))
                    else:await ws.send_str(packet['data'])
                async for m in ws:
                    if m.type in (aiohttp.WSMsgType.TEXT,aiohttp.WSMsgType.BINARY):
                        await self.emit({'type':'preview_data','client':client,'binary':m.type==aiohttp.WSMsgType.BINARY,'data':base64.b64encode(m.data).decode() if m.type==aiohttp.WSMsgType.BINARY else m.data})
        except Exception as e:self.log('preview',str(e))
        finally:self.preview_ws.pop(client,None);await self.emit({'type':'preview_close','client':client})

    async def sync_loop(self):
        last_tree=0;last_save=time.monotonic();last_error=''
        while True:
            await asyncio.sleep(1)
            try:
                try:await self.flush_events()
                except Exception as log_error:
                    if str(log_error)!=last_error:self.log('log-sync',str(log_error));last_error=str(log_error)
                await self.request('POST','/telemetry',json={'logs_pending':len(self.events)})
                if not self.ready or self.quiesced or self.frozen:continue
                async with self.serial:
                    await self.sync_config()
                    if time.monotonic()-last_tree>30:
                        tree=[{'root':'workspace','path':'.'}]
                        for base,dirs,_ in os.walk(self.root,followlinks=False):
                            dirs[:]=[d for d in dirs if not d.startswith('.')]
                            for d in dirs:
                                tree.append({'root':'workspace','path':str((pathlib.Path(base)/d).relative_to(self.root))})
                                if len(tree)>=5000:break
                            if len(tree)>=5000:break
                        baked=pathlib.Path('/opt/irv/image-tree.json')
                        if baked.exists():tree+=json.loads(baked.read_text())
                        await self.request('POST','/telemetry',json={'tree':tree});last_tree=time.monotonic()
                    if time.monotonic()-last_save>300:
                        await self.snapshot();last_save=time.monotonic()
                last_error=''
            except Exception as e:
                if str(e)!=last_error:self.log('sync',str(e));last_error=str(e)
                with contextlib.suppress(Exception):await self.request('POST','/config/ack',json={'error':str(e)})

    async def run(self):
        if not TOKEN:raise ValueError('IRV_AGENT_TOKEN is required')
        timeout=aiohttp.ClientTimeout(total=45)
        async with aiohttp.ClientSession(headers={'Authorization':'Bearer '+TOKEN},timeout=timeout) as http:
            self.http=http
            bootstrap=await self.request('GET','/bootstrap');self.session=bootstrap.get('session');c=validate_config(bootstrap['config'])
            self.root=BASE/c['folder_name'];self.root.mkdir(parents=True,exist_ok=True)
            # Friendly home alias when the working copy resides on a mounted volume.
            home=HOME_DIR/c['folder_name']
            if home!=self.root and not home.exists():home.symlink_to(self.root)
            self.task(self.sync_loop())
            try:
                await self.restore(bootstrap.get('snapshot',{}))
                if (self.root/'config.toml').is_file():
                    existing={n:safe_path(self.root,n).read_text() for n in bootstrap['files'] if safe_path(self.root,n).is_file()}
                    old=(self.root/'config.toml').read_text()
                    if old!=bootstrap['config'] or existing!=bootstrap['files']:
                        await self.request('POST','/config/recovery',json={'config':old,'files':existing})
                self.managed=ManagedFiles(self.root,self.private/'managed',int(os.environ.get('IRV_DEV_UID','1000')))
                intent=self.managed.state/'applying.json'
                if intent.exists():self.overlay(json.loads(intent.read_text()))
                elif self.managed.marker.exists():
                    marker=json.loads(self.managed.marker.read_text());self.managed.verify()
                    self.revision=marker['revision'];self.files={n:(self.root/n).read_text() for n in marker['files'] if n!='config.toml'}
                    self.config=validate_config((self.root/'config.toml').read_text());self.baseline=self.bundle();self.keywords()
                else:
                    saved_revision=bootstrap.get('snapshot',{}).get('config_revision',0)
                    if saved_revision:
                        versions=await self.request('GET','/config/changes?after='+str(saved_revision-1))
                        baseline=next((json.loads(v['bundle']) for v in versions['versions'] if json.loads(v['bundle'])['revision']==saved_revision),None)
                        if baseline is None:raise ValueError('Snapshot configuration revision is missing')
                        self.overlay(baseline)
                await self.sync_config();await self.start_local_api();await self.setup();self.ready=True
            except Exception as e:self.log('startup',traceback.format_exc())
            while True:
                try:
                    async with http.ws_connect(API.replace('https://','wss://').replace('http://','ws://')+'/ws/agent',heartbeat=15,max_msg_size=12*1024*1024) as ws:
                        self.ws=ws
                        if self.ready:await self.emit({'type':'ready'})
                        else:await self.emit({'type':'error','message':'Startup failed; inspect startup logs and edit entrypoint, then restart'})
                        async for msg in ws:
                            if msg.type==aiohttp.WSMsgType.TEXT:
                                try:await self.dispatch(json.loads(msg.data))
                                except Exception as e:self.log('agent',str(e))
                except Exception as e:self.log('connection',str(e))
                await asyncio.sleep(2)

if __name__=='__main__':
    try:asyncio.run(Agent().run())
    except KeyboardInterrupt:pass
