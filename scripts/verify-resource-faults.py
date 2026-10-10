#!/usr/bin/env python3
"""Explicit local native fallback QA with a wrapper agent; official assets stay intact."""
import argparse,hashlib,importlib.util,json,shutil,subprocess,tempfile,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--out',type=Path,required=True)
p.add_argument('--versions',nargs='+',default=['1.13','1.14.3','1.16.5','1.19.4','1.21.4','1.21.5','1.21.6','26.2','26.3'])
p.add_argument('--cache',type=Path,default=repo/'.local/cache')
p.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
p.add_argument('--docker-context',default='colima-mcicon')
p.add_argument('--docker-image',default='mcicon-render:dev')
p.add_argument('--compare',type=Path,help='Optional normal full-export baseline for the healthy item')
p.add_argument('--faults',nargs='+',choices=['model','texture'],default=['model','texture'])
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
if len(a.faults)!=len(set(a.faults)) or len(a.versions)!=len(set(a.versions)):p.error('Use unique faults and versions')
sources={str(path.relative_to(repo)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [Path(__file__).resolve(),repo/'scripts/qa/ResourceFaultAgent.java']}
results=[]
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
with tempfile.TemporaryDirectory(prefix='.cli-snapshot-',dir=out) as temporary:
 distribution=Path(temporary)/'mcicon';shutil.copytree(a.cli.resolve().parent.parent,distribution)
 agent=distribution/'worker/worker-agent.jar';agent_hash=hashlib.sha256(agent.read_bytes()).hexdigest()
 classes=out/'classes';classes.mkdir()
 subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(repo/'scripts/qa/ResourceFaultAgent.java')],check=True)
 for fault in a.faults:
  injected=out/(fault+'-worker.jar')
  with zipfile.ZipFile(agent) as source,zipfile.ZipFile(injected,'w',compression=zipfile.ZIP_DEFLATED) as target:
   for entry in source.infolist():
    data=source.read(entry.filename)
    if entry.filename=='META-INF/MANIFEST.MF':
     data=b'Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.ResourceFaultAgent\r\n\r\n'
    target.writestr(entry.filename,data)
   for path in classes.rglob('*.class'):target.write(path,path.relative_to(classes).as_posix())
   target.writestr('mcicon-resource-fault.txt',fault)
  for v in a.versions:
   active=subprocess.run(['docker','--context',a.docker_context,'ps','--format','{{.Names}}'],text=True,capture_output=True,check=True).stdout.splitlines()
   if sum(name.startswith('mcicon-worker-') for name in active)>1:raise RuntimeError('At most two native workers are allowed; wait for other QA to finish')
   root=out/fault/v;root.mkdir(parents=True)
   with (root/'result.json').open('w') as stdout,(root/'cli.log').open('w') as stderr:
    run=subprocess.run([str(distribution/'bin/mcicon'),'render','--version',v,'--cache',str(a.cache.resolve()),'--offline','--id','stone','--id','wheat','--allow-experimental','--out',str(root),'--worker-jar',str(injected),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--json'],stdout=stdout,stderr=stderr)
   manifest=root/'java'/v/'icons/manifest.json'
   m=json.loads(manifest.read_text()) if manifest.exists() else {}
   entries={e['id']:e for e in m.get('entries',[])};e=entries.get('minecraft:stone',{});healthy=entries.get('minecraft:wheat',{});expected='MISSING_'+fault.upper()
   diagnostics=e.get('diagnostics',[])
   codes=[d.get('code') for d in diagnostics]
   record={'version':v,'fault':fault,'exitCode':run.returncode,'code':e.get('code'),'diagnosticCodes':codes,'pngFiles':len(list(root.rglob('*.png'))),'counts':m.get('counts'),'continuedHealthyItem':healthy.get('status')=='success','fallbackPngWritten':(manifest.parent/e.get('path','absent.png')).is_file()}
   record.update(qaAgentSha256=m.get('settings',{}).get('agentSha256'),sourceSha256=m.get('sourceSha256'),healthyPngIntegrity=False,healthyRgbaMatchesBaseline=None)
   if record['continuedHealthyItem']:
    try:
     data=(manifest.parent/healthy['path']).read_bytes();width,height,rgba=audit.rgba_png(data)
     record['healthyPngIntegrity']=hashlib.sha256(data).hexdigest()==healthy['sha256'] and width==height==64 and any(rgba[3::4]) and not any(rgba[i] or rgba[i+1] or rgba[i+2] for i in range(0,len(rgba),4) if not rgba[i+3])
     if a.compare:
      reference=a.compare.resolve()/'java'/v/'icons/manifest.json';baseline=json.loads(reference.read_text());entry=next(e for e in baseline['entries'] if e['id']=='minecraft:wheat')
      original=(reference.parent/entry['path']).read_bytes()
      record['healthyRgbaMatchesBaseline']=hashlib.sha256(original).hexdigest()==entry['sha256'] and audit.rgba_png(original)==(width,height,rgba) and entry['path']==healthy['path'] and baseline['sourceSha256']==m['sourceSha256']
    except Exception as error:record['healthyAuditError']=str(error)
   record['passed']=run.returncode==3 and record['pngFiles']==1 and not record['fallbackPngWritten'] and record['continuedHealthyItem'] and record['healthyPngIntegrity'] and (not a.compare or record['healthyRgbaMatchesBaseline']) and (record['code']==expected or expected in codes) and m.get('counts',{}).get('succeeded')==1 and m.get('counts',{}).get('failed')==1
   results.append(record);(out/'report.json').write_text(json.dumps({'productionAgentSha256':agent_hash,'syntheticFaultInjection':True,'officialInputsModified':False,'harnessSourcesSha256':sources,'injectionScope':{'model':'Resolved item model replaced with vanilla fallback','texture':'Consumed sprite observer input replaced with vanilla missing atlas sprite'},'results':results},indent=2)+'\n')
   print(v,fault,'PASS' if record['passed'] else 'FAIL',flush=True)
raise SystemExit(any(not r['passed'] for r in results))
