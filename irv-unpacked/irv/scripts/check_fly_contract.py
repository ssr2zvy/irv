#!/usr/bin/env python3
"""Fail closed on removed endpoints/new required fields in the used API schemas."""
import json,os,pathlib,urllib.request,re,sys
root=pathlib.Path(__file__).resolve().parents[1]
url=os.environ.get('FLY_OPENAPI_URL','https://docs.machines.dev/openapi.json')
with urllib.request.urlopen(url,timeout=30) as r: current=json.load(r)
if not isinstance(current.get('paths'),dict):raise SystemExit('Fly OpenAPI response has no paths; update blocked')
required={'/apps':{'post'},'/apps/{app_name}/machines':{'post','get'},'/apps/{app_name}/machines/{machine_id}':{'get','delete'},'/apps/{app_name}/machines/{machine_id}/start':{'post'},'/apps/{app_name}/machines/{machine_id}/stop':{'post'},'/apps/{app_name}/machines/{machine_id}/suspend':{'post'},'/apps/{app_name}/volumes':{'post'},'/apps/{app_name}/volumes/{volume_id}':{'delete'}}
def normalize(p):return re.sub(r'\{[^}]+\}','{}',p.removeprefix('/v1'))
paths={normalize(k):v for k,v in current['paths'].items()}
for path,methods in required.items():
 if not methods<=set(paths.get(normalize(path),{})):raise SystemExit('Required Fly API method disappeared: '+path)
# Keep full current schema as a CI artifact. Changes need a passing live lifecycle canary.
out=root/'build';out.mkdir(exist_ok=True);(out/'fly-openapi.json').write_text(json.dumps(current,indent=2))
baseline=root/'contracts/fly-openapi.json'
if baseline.exists():
 previous=json.loads(baseline.read_text())
 def required_fields(s):
  result={}
  def walk(x,p=''):
   if isinstance(x,dict):
    if isinstance(x.get('required'),list):result[p]=set(x['required'])
    for k,v in x.items():walk(v,p+'/'+k)
   elif isinstance(x,list):
    for i,v in enumerate(x):walk(v,p+'/'+str(i))
  walk(s);return result
 old,new=required_fields(previous),required_fields(current)
 additions={k:sorted(v-old.get(k,set())) for k,v in new.items() if v-old.get(k,set())}
 if additions:raise SystemExit('New required schema fields; adapter review required: '+json.dumps(additions))
print('Required API methods present. Live canary is still mandatory before promotion.')
