#!/usr/bin/env python3
"""irv sandbox supervisor. Runs unprivileged; only its sandbox-scoped token is present."""
import asyncio, base64, contextlib, fcntl, hashlib, json, os, pathlib, pty, re, shlex
import signal, struct, subprocess, sys, termios, time, tomllib, uuid
import aiohttp

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
    if set(c) - {'schema_version','folder_name','entrypoint','preview_ports','keywords','processes'}:
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


class Agent:
    def __init__(self):
        self.session = None; self.http = None; self.ws = None; self.root = None; self.config = {}; self.revision = 0
        self.files = {}; self.baseline = None; self.terminals = {}; self.processes = {}
        self.frozen = []; self.env = {}; self.ready = False; self.events = []
        self.serial = asyncio.Lock(); self.event_lock = asyncio.Lock(); self.quiesced = False; self.tasks = set(); self.preview_ws = {}; self.pending_preview = {}
        RUNTIME.mkdir(parents=True, exist_ok=True)
        self.logfile = RUNTIME/'events.jsonl'
        if self.logfile.exists():
            for line in self.logfile.read_text().splitlines():
                with contextlib.suppress(Exception): self.events.append(json.loads(line))

    def task(self, coro):
        t = asyncio.create_task(coro); self.tasks.add(t); t.add_done_callback(self.tasks.discard); return t

    def log(self, source, message):
        e = dict(id=uuid.uuid4().hex, source=source, message=str(message)[:16000], session=self.session)
        self.events.append(e)
        with self.logfile.open('a') as f: f.write(json.dumps(e)+'\n')
        print(f'[{source}] {str(message)[:16000]}', flush=True)

    async def request(self, method, path, **kw):
        async with self.http.request(method, API+'/agent'+path, **kw) as res:
            if res.status >= 300: raise RuntimeError(f'{method} {path}: {res.status} {(await res.text())[:300]}')
            if path.startswith('/chunks/') and method == 'GET': return await res.read()
            return await res.json()

    async def emit(self, data):
        if self.ws and not self.ws.closed: await self.ws.send_json(data)

    def bundle(self):
        text = (self.root/'config.toml').read_text()
        c = validate_config(text)
        names = set(self.files) | {c['entrypoint']}
        scripts = self.root/'scripts'
        if scripts.is_dir():
            names |= {str(p.relative_to(self.root)) for p in scripts.rglob('*') if p.is_file() and p.stat().st_size <= 65536}
        if len(names)>32: raise ValueError('At most 32 startup files')
        files = {n:safe_path(self.root,n).read_text() for n in sorted(names) if safe_path(self.root,n).is_file()}
        return {'config':text,'files':files}

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

    def overlay(self, data, force=False):
        c = validate_config(data['config'])
        if self.root and c['folder_name'] != self.root.name: raise ValueError('Folder cannot change after first start')
        if not force and self.baseline is not None and self.bundle() != self.baseline:
            atomic(RUNTIME/'remote-config-conflict.json',json.dumps(data)); raise ValueError('Local edits conflict with UI edits; pending remote version saved in /tmp/irv-runtime/remote-config-conflict.json')
        atomic(self.root/'config.toml',data['config'],0o644)
        for path,text in data['files'].items(): atomic(safe_path(self.root,path),text,0o644)
        self.files=data['files']; self.config=c; self.revision=data['revision']; self.baseline=self.bundle(); self.keywords()

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
        await self.request('POST','/telemetry',json={'kind':'restore','seconds':time.monotonic()-start,'bytes':sum(e.get('size',0) for e in manifest['entries'])})

    def user_pids(self):
        result=[]
        for p in pathlib.Path('/proc').iterdir():
            if not p.name.isdigit() or int(p.name)==os.getpid(): continue
            try:
                if p.stat().st_uid==os.getuid() and int(p.name)>1:result.append(int(p.name))
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
                await self.request('POST','/events',json=batch)
                del self.events[:len(batch)]
                atomic(self.logfile,''.join(json.dumps(e)+'\n' for e in self.events))

    async def sync_config(self):
        bundle=self.bundle()
        if bundle!=self.baseline:
            result=await self.request('POST','/config',json={**bundle,'revision':self.revision})
            self.revision=result['revision'];self.files=bundle['files'];self.config=validate_config(bundle['config']);self.baseline=bundle;self.keywords()

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
            result=await self.request('POST','/snapshots',json={'version':1,'entries':entries})
            self.log('save',f'Saved {total} bytes; uploaded {uploaded} new bytes; snapshot {result["snapshot"]}')
            await self.request('POST','/telemetry',json={'kind':'save','seconds':time.monotonic()-start,'bytes':uploaded})
            if final:await self.flush_events()
            return {'verified':True,'logs_flushed':final,**result}
        except BaseException:
            self.unfreeze();self.quiesced=False;self.ready=was_ready
            raise
        finally:
            if not final:self.unfreeze()

    async def pipe_log(self, reader, source):
        while True:
            line=await reader.readline()
            if not line:break
            self.log(source,line.decode(errors='replace').rstrip())

    async def setup(self):
        start=time.monotonic();self.env={k:v for k,v in os.environ.items() if not k.startswith(('IRV_','AWS_','FLY_'))}
        self.env.update(HOME=str(HOME_DIR),USER='dev',WORKSPACE_DIR=str(self.root),TERM='xterm-256color')
        path=safe_path(self.root,self.config['entrypoint']);out=RUNTIME/'environment.json'
        # Source entrypoint in a shell and capture exported variables from that same shell.
        code=f'set -e; source {shlex.quote(str(path))}; python3 -c '+shlex.quote('import os,json;open('+repr(str(out))+',"w").write(json.dumps(dict(os.environ)))')
        p=await asyncio.create_subprocess_exec('/bin/bash','-c',code,cwd=self.root,env=self.env,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT,start_new_session=True)
        log=self.task(self.pipe_log(p.stdout,'entrypoint'))
        try:await asyncio.wait_for(p.wait(),90)
        except asyncio.TimeoutError:os.killpg(p.pid,signal.SIGKILL);raise ValueError('Entrypoint exceeded 90-second setup budget')
        await log
        if p.returncode!=0:raise ValueError(f'Entrypoint exited {p.returncode}')
        self.env=json.loads(out.read_text());self.keywords()
        await self.request('POST','/telemetry',json={'kind':'setup','seconds':time.monotonic()-start})
        await self.start_processes()

    async def start_processes(self):
        for name,spec in self.config.get('processes',{}).items():
            if name in self.processes and self.processes[name].returncode is None:continue
            p=await asyncio.create_subprocess_exec('/bin/bash','-c',spec['command'],cwd=safe_path(self.root,spec.get('cwd','.')),env=self.env,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT,start_new_session=True)
            self.processes[name]=p;self.task(self.pipe_log(p.stdout,'process:'+name));self.task(self.process_end(name,p))
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
                        line=f.readline(16000)
                        if not line:break
                        self.log('file:'+name,line.rstrip())
                    offset=f.tell()
            await asyncio.sleep(1)

    async def terminal(self, client):
        if not self.ready:raise ValueError('Environment is not ready')
        master,slave=pty.openpty();os.set_blocking(master,False)
        proc=subprocess.Popen(['/bin/bash','--rcfile',str(RUNTIME/'bashrc'),'-i'],cwd=self.root,env=self.env,stdin=slave,stdout=slave,stderr=slave,start_new_session=True)
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
                    elif op=='config':
                        if self.quiesced:raise ValueError('Final checkpoint in progress; app configuration remains saved for next startup')
                        self.overlay(data);result={'revision':self.revision}
                    elif op in ('resume','unfreeze'):
                        self.unfreeze();self.quiesced=False;boot=await self.request('GET','/bootstrap');self.session=boot.get('session');self.overlay(boot);await self.start_processes();self.ready=True;await self.emit({'type':'ready'});result={'ok':True}
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
                await self.flush_events()
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
                await self.restore(bootstrap.get('snapshot',{}));self.overlay(bootstrap,force=True);await self.setup();self.ready=True
            except Exception as e:self.log('startup',str(e))
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
