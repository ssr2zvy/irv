"""Root-owned managed files; ordinary development processes never receive write rights."""
import os,pathlib,json,hashlib

class ManagedFiles:
    def __init__(self,root,state,uid=1000):
        self.root=pathlib.Path(root);self.state=pathlib.Path(state);self.uid=uid
        if self.state.is_symlink():raise ValueError('Managed state cannot be a symlink')
        self.state.mkdir(parents=True,exist_ok=True);os.chmod(self.state,0o700)
        if os.geteuid()==0:os.chown(self.state,0,0)
        self.marker=self.state/'applied.json'
    def path(self,name):
        p=pathlib.PurePosixPath(name)
        if p.is_absolute() or str(p)!=name or name in ('','.','..') or '..' in p.parts or '\\' in name or '\0' in name:raise ValueError('Invalid managed path')
        target=self.root
        for part in p.parts:
            target=target/part
            if target.is_symlink():raise ValueError('Managed paths cannot contain symlinks')
        return target
    def parents(self,target):
        self.root.mkdir(parents=True,exist_ok=True)
        os.chown(self.root,0,0);os.chmod(self.root,0o1777)
        cur=self.root
        for part in target.relative_to(self.root).parts[:-1]:
            cur=cur/part
            if cur.is_symlink():raise ValueError('Managed parent cannot be a symlink')
            cur.mkdir(exist_ok=True);os.chown(cur,0,0);os.chmod(cur,0o1777)
    def apply(self,bundle,atomic):
        if os.geteuid()!=0:raise RuntimeError('Managed sync requires the privileged agent; user programs run as dev')
        files={'config.toml':bundle['config'],**bundle['files']}
        previous=json.loads(self.marker.read_text()) if self.marker.exists() else {'files':{}}
        # Durable intent lets startup finish a partially applied revision after a crash.
        atomic(self.state/'applying.json',json.dumps(bundle))
        for name,text in files.items():
            p=self.path(name);self.parents(p);atomic(p,text,0o444)
        for name in set(previous['files'])-set(files):
            p=self.path(name)
            if p.exists():
                if name in bundle.get('delete',[]):p.unlink()
                else:os.chown(p,self.uid,self.uid);os.chmod(p,0o644)
        # Deletions may be replayed on a fresh restore before a marker exists.
        for name in bundle.get('delete',[]):
            if name in files:raise ValueError('Cannot delete an active managed file')
            p=self.path(name)
            if p.is_file():p.unlink()
        atomic(self.marker,json.dumps({'revision':bundle['revision'],'files':{n:hashlib.sha256(t.encode()).hexdigest() for n,t in files.items()}}))
        (self.state/'applying.json').unlink(missing_ok=True)
    def verify(self):
        if not self.marker.exists():return
        for n,h in json.loads(self.marker.read_text())['files'].items():
            p=self.path(n)
            if not p.is_file() or p.stat().st_uid!=0 or p.stat().st_mode&0o222 or hashlib.sha256(p.read_bytes()).hexdigest()!=h:raise RuntimeError('Managed file changed outside irv: '+n)
