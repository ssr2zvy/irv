import React,{useEffect,useRef,useState} from 'react';
import TOML from '@iarna/toml';
import {api,parse} from './api';
export default function Configuration({box,refresh}) {
 const [status,setStatus]=useState(null),[lease,setLease]=useState(null),[config,setConfig]=useState(box.config),[files,setFiles]=useState(parse(box.files,{})),[revision,setRevision]=useState(box.revision),[selected,setSelected]=useState('config.toml'),[message,setMessage]=useState(''),[dirty,setDirty]=useState(false),[deleted,setDeleted]=useState([]),[keyword,setKeyword]=useState(''),[folder,setFolder]=useState('0');
 const editor=useRef(crypto.randomUUID()),version=useRef(0),queue=useRef(Promise.resolve()),current=useRef(null),leaseRef=useRef(null);
 current.current={config,files,revision,delete:deleted};leaseRef.current=lease;
 const base=`/sandboxes/${box.id}/sync`;
 async function poll(){const s=await api(base);setStatus(s);return s}
 useEffect(()=>{poll().catch(e=>setMessage(e.message));const t=setInterval(()=>poll().catch(()=>{}),2000);return()=>clearInterval(t)},[box.id]);
 useEffect(()=>{if(leaseRef.current&&status&&(status.lease.editor!==editor.current||status.lease.expires<=Date.now())){setLease(null);setMessage('Editing session ended or was taken over. Your browser draft is retained.')}},[status]);
 useEffect(()=>{if(lease||dirty||!status)return;setConfig(status.config);setFiles(status.files);setRevision(status.revision)},[status?.revision,lease,dirty]);
 const auth=()=>({editor:editor.current,fence:leaseRef.current?.fence,lease_token:leaseRef.current?.lease_token});
 useEffect(()=>{if(!lease)return;const t=setInterval(()=>api(base+'/renew','POST',auth()).catch(e=>{setLease(null);setMessage('Editing rights lost. Your browser draft is retained. '+e.message)}),20000);return()=>clearInterval(t)},[lease?.fence]);
 useEffect(()=>{if(dirty)localStorage.setItem('irv-draft:'+box.id,JSON.stringify(current.current));},[config,files,dirty,deleted]);
 // A saved draft is independent of whether it can be activated. Serialize all saves.
 function submit(commit=false,override={}) {
  const snapshot={...current.current,...override};const credentials=auth();
  const run=async()=>{const body={...snapshot,...credentials,draft_version:++version.current,...(commit?{request_id:crypto.randomUUID()}: {})};
   let r;try{r=await api(base+'/'+(commit?'commit':'draft'),'POST',body)}catch(e){if(!commit)throw e;r=await api(base+'/commit','POST',body)}
   if(leaseRef.current?.fence!==credentials.fence)return r;
   if(r.committed){setRevision(r.revision);current.current.revision=r.revision;setDirty(false);setDeleted([]);localStorage.removeItem('irv-draft:'+box.id);setMessage('Saved in app. Waiting for machine acknowledgement.');refresh()}
   else if(r.error)setMessage('Draft saved: '+r.error);
   else setMessage('Draft saved. Save changes to publish a valid revision.');
   await poll();return r;
  };
  const p=queue.current.then(run);queue.current=p.catch(e=>setMessage(e.message));return p;
 }
 useEffect(()=>{if(!lease||!dirty)return;const t=setTimeout(()=>submit(false).catch(()=>{}),1000);return()=>clearTimeout(t)},[config,files,deleted,lease?.fence]);
 async function begin(takeover=false){try{const r=await api(base+'/acquire','POST',{editor:editor.current,takeover});setLease(r);leaseRef.current=r;version.current=r.draft?.version||0;setConfig(r.config);setFiles(r.files);setRevision(r.revision);setDeleted([]);setDirty(false);setMessage('Editing session acquired. Other editors are read-only.')}catch(e){setMessage(e.message)}}
 async function finish(){try{await queue.current;if(dirty)await submit(false);await api(base+'/release','POST',auth());setLease(null);setDirty(false);setMessage('Editing finished; saved draft remains recoverable.');poll()}catch(e){setMessage(e.message)}}
 async function recover(e){try{const r=await api(base+'/recover','POST',{...auth(),draft_editor:e});const b=JSON.parse(r.bundle);setConfig(b.config);setFiles(b.files);setDeleted(b.delete||[]);setRevision(r.base_revision);setDirty(true);setMessage('Recovered draft. Review against the current committed configuration before saving.')}catch(e){setMessage(e.message)}}
 function edit(value){setDirty(true);selected==='config.toml'?setConfig(value):setFiles({...files,[selected]:value})}
 function script(){try{const path=prompt('Helper path inside workspace','scripts/helper.sh');if(!path)return;const c=TOML.parse(config);c.helpers=[...new Set([...(c.helpers||[]),path])];setConfig(TOML.stringify(c));setFiles({...files,[path]:files[path]||'#!/usr/bin/env bash\n'});setSelected(path);setDirty(true)}catch(e){setMessage(e.message)}}
 function removeScript(removeFile){try{const c=TOML.parse(config);if(selected===c.entrypoint)throw Error('Choose a different entrypoint before removing this file');c.helpers=(c.helpers||[]).filter(p=>p!==selected);const f={...files};delete f[selected];setConfig(TOML.stringify(c));setFiles(f);if(removeFile)setDeleted([...deleted,selected]);setSelected('config.toml');setDirty(true)}catch(e){setMessage(e.message)}}
 const tree=parse(box.tree,[]);let keywords={};try{keywords=TOML.parse(config).keywords||{}}catch{}
 function keywordSave(name,remove=false){try{const c=TOML.parse(config);c.keywords={...c.keywords};if(remove)delete c.keywords[name];else{const t=tree[Number(folder)];if(!t)throw Error('No saved directory selected');c.keywords[name]={root:t.root,path:t.path}}
  const text=TOML.stringify(c);setConfig(text);setDirty(true);submit(true,{config:text}).catch(()=>{});setKeyword('');
 }catch(e){setMessage(e.message)}}
 const held=status?.lease?.expires>Date.now(),own=held&&status.lease.editor===editor.current;
 return <div className="configuration"><div className="notice">{!status?'Loading synchronization status':status?.error?'Sync error: '+status.error:status?.applied_revision===status?.revision?(['running','starting','saving'].includes(box.state)?'Synced to machine':'Saved in app · environment offline'):'Saved in app · pending machine'} · revision {revision}{dirty?' · draft':''}{held&&!own?` · editing in ${status.lease.origin}`:''}</div>
 <div className="editor-tools">{!lease?<><button className="primary" onClick={()=>begin()}>Edit configuration</button>{held&&!own&&<button onClick={()=>{if(confirm('Revoke the other editing session? Its saved draft will be retained.'))begin(true)}}>Take over</button>}</>:<><button className="primary" onClick={()=>submit(true).catch(()=>{})}>Save changes</button><button onClick={finish}>Finish editing</button></>}
 <select value={selected} onChange={e=>setSelected(e.target.value)}>{['config.toml',...Object.keys(files)].map(p=><option key={p}>{p}</option>)}</select><button disabled={!lease} onClick={script}>＋ Helper</button>{selected!=='config.toml'&&<><button disabled={!lease} onClick={()=>removeScript(false)}>Unmanage</button><button disabled={!lease} onClick={()=>{if(confirm('Delete this helper from the machine at synchronization?'))removeScript(true)}}>Delete file</button></>}</div>
 {message&&<div className="notice" role="status">{message}</div>}
 {revision!==status?.revision&&lease&&<div className="notice">This draft is based on revision {revision}; current is {status?.revision}. <button onClick={()=>{if(confirm('I reviewed the draft against the latest configuration and want to use it as the new version.')){setRevision(status.revision);setDirty(true)}}}>Use latest as reviewed base</button><details><summary>Current committed configuration</summary><pre>{JSON.stringify({config:status?.config,files:status?.files},null,2)}</pre></details></div>}
 <textarea className="code-editor" readOnly={!lease} spellCheck="false" aria-label={selected} value={selected==='config.toml'?config:files[selected]||''} onChange={e=>edit(e.target.value)}/>
 <button disabled={box.state!=='running'||dirty} onClick={async()=>{if(confirm('Apply saved setup and restart running sandbox processes?')){try{await api(`/sandboxes/${box.id}/apply`,'POST');setMessage('Applying setup. Process output is available in Logs.')}catch(e){setMessage(e.message)}}}}>Apply setup & restart processes</button>
 {box.state==='stopped'&&<button onClick={async()=>{if(confirm('Recreate the stopped Machine with the administrator-selected image? Its working volume stays intact.')){try{await api(`/sandboxes/${box.id}/rebuild-agent`,'POST');setMessage('Preparing image upgrade. Start the environment once its state becomes cold.');refresh()}catch(e){setMessage(e.message)}}}}>Recreate stopped Machine for image upgrade</button>}
 <p>Setup runs on the next environment start. Saving never executes your scripts. Keyword changes appear at the next shell prompt.</p>
 <section className="card"><h2>Directory keywords</h2>{Object.entries(keywords).map(([name,k])=><div className="editor-tools" key={name}><code>{name} → {k.root}/{k.path}</code><button disabled={!lease} onClick={()=>{setKeyword(name);const i=tree.findIndex(t=>t.root===k.root&&t.path===k.path);setFolder(String(Math.max(0,i)))}}>Edit</button><button disabled={!lease} onClick={()=>keywordSave(name,true)}>Delete</button></div>)}<div className="keyword-row"><input disabled={!lease} value={keyword} onChange={e=>setKeyword(e.target.value)} placeholder="Keyword"/><select disabled={!lease} value={folder} onChange={e=>setFolder(e.target.value)}>{tree.map((t,i)=><option key={i} value={i}>{t.root}/{t.path}</option>)}</select><button disabled={!lease||!keyword} onClick={()=>keywordSave(keyword)}>Save keyword</button></div></section>
 <section className="card"><h2>Saved drafts</h2>{status?.drafts?.map(d=><p key={d.editor_id}>{new Date(d.updated).toLocaleString()} · base {d.base_revision} <button disabled={!lease} onClick={()=>recover(d.editor_id)}>Recover draft</button></p>)}<button disabled={!lease} onClick={()=>{const b=parse(localStorage.getItem('irv-draft:'+box.id),null);if(b){setConfig(b.config);setFiles(b.files);setRevision(b.revision);setDeleted(b.delete||[]);setDirty(true)}else setMessage('No browser draft available')}}>Recover browser draft</button></section></div>
}
