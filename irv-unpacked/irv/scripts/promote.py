#!/usr/bin/env python3
import json,os,pathlib,sys,urllib.request
body=json.dumps({k:pathlib.Path(k+'-digest.txt').read_text().strip() for k in ['blank','java']}).encode()
req=urllib.request.Request(sys.argv[1].rstrip('/')+'/maintenance/promote',data=body,headers={'Content-Type':'application/json','Authorization':'Bearer '+os.environ['MAINTENANCE_TOKEN']})
with urllib.request.urlopen(req,timeout=30) as r:print(r.status)
