#!/usr/bin/env python3
"""観測入力の置換、または実際の破損パック読み込みで欠損診断を検証する。"""
import argparse,hashlib,importlib.util,io,json,shutil,subprocess,tempfile,zipfile
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
p.add_argument('--faults',nargs='+',choices=['model','texture','special-texture'],default=['model','texture'])
p.add_argument('--injection',choices=['observer','resource-pack'],default='observer',help='resource-pack は描画・観測を変更せず破損ファイルをゲームに読み込ませる')
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
if len(a.faults)!=len(set(a.faults)) or len(a.versions)!=len(set(a.versions)):p.error('Use unique faults and versions')
if 'special-texture' in a.faults and a.injection!='resource-pack':p.error('special-texture は resource-pack 専用です')
helper='ResourcePackFaultAgent' if a.injection=='resource-pack' else 'ResourceFaultAgent'
sources={str(path.relative_to(repo)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [Path(__file__).resolve(),repo/('scripts/qa/'+helper+'.java')]}
def resource_pack(client,fault):
 with zipfile.ZipFile(client) as jar:
  if 'version.json' in jar.namelist():
   version=json.loads(jar.read('version.json'))['pack_version']
   if isinstance(version,int):pack={'pack_format':version}
   elif 'resource' in version:pack={'pack_format':version['resource']}
   else:pack={'min_format':[version['resource_major'],version['resource_minor']],'max_format':[version['resource_major'],version['resource_minor']]}
  else:pack=json.loads(jar.read('pack.mcmeta'))['pack']
  pack['description']='MCIconGenerator QA: 破損リソース'
  path={'model':'assets/minecraft/models/item/stone.json','texture':'assets/minecraft/textures/block/stone.png','special-texture':'assets/minecraft/textures/entity/chest/normal.png'}[fault]
  if fault=='model' and path not in jar.namelist():
   path='assets/minecraft/items/stone.json'
  original=jar.read(path)
 data=io.BytesIO()
 with zipfile.ZipFile(data,'w',compression=zipfile.ZIP_DEFLATED) as target:
  target.writestr('pack.mcmeta',json.dumps({'pack':pack},ensure_ascii=False).encode())
  target.writestr(path,b'{' if fault=='model' else b'MCIconGenerator QA: invalid PNG')
 return data.getvalue(),path,hashlib.sha256(original).hexdigest()
results=[]
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
with tempfile.TemporaryDirectory(prefix='.cli-snapshot-',dir=out) as temporary:
 distribution=Path(temporary)/'mcicon';shutil.copytree(a.cli.resolve().parent.parent,distribution)
 agent=distribution/'worker/worker-agent.jar';agent_hash=hashlib.sha256(agent.read_bytes()).hexdigest()
 classes=out/'classes';classes.mkdir()
 subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(repo/('scripts/qa/'+helper+'.java'))],check=True)
 for fault in a.faults:
  for v in a.versions:
   injected=out/(fault+'-'+v+'-worker.jar')
   client=a.cache.resolve()/'versions'/v/'client.jar';source_hash=hashlib.sha256(client.read_bytes()).hexdigest()
   pack,path,original_hash=resource_pack(client,fault) if a.injection=='resource-pack' else (None,None,None)
   with zipfile.ZipFile(agent) as source,zipfile.ZipFile(injected,'w',compression=zipfile.ZIP_DEFLATED) as target:
    for entry in source.infolist():
     data=source.read(entry.filename)
     if entry.filename=='META-INF/MANIFEST.MF':data=('Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.'+helper+'\r\n\r\n').encode()
     target.writestr(entry.filename,data)
    for compiled in classes.rglob('*.class'):target.write(compiled,compiled.relative_to(classes).as_posix())
    if pack:
     target.writestr('mcicon-resource-pack.zip',pack)
     target.writestr('mcicon-resource-path.txt','minecraft:'+path.removeprefix('assets/minecraft/'))
     target.writestr('mcicon-resource-pack-options.txt','resourcePacks:["file/mcicon-qa.zip"]\nincompatibleResourcePacks:[]\n')
    else:target.writestr('mcicon-resource-fault.txt',fault)
   active=subprocess.run(['docker','--context',a.docker_context,'ps','--format','{{.Names}}'],text=True,capture_output=True,check=True).stdout.splitlines()
   if sum(name.startswith('mcicon-worker-') for name in active)>1:raise RuntimeError('At most two native workers are allowed; wait for other QA to finish')
   root=out/fault/v;root.mkdir(parents=True)
   old_runs=set(a.cache.resolve().glob('worker-*'))
   selected='chest' if fault=='special-texture' else 'stone'
   with (root/'result.json').open('w') as stdout,(root/'cli.log').open('w') as stderr:
    run=subprocess.run([str(distribution/'bin/mcicon'),'render','--version',v,'--cache',str(a.cache.resolve()),'--offline','--id',selected,'--id','wheat','--allow-experimental','--out',str(root),'--worker-jar',str(injected),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--json'],stdout=stdout,stderr=stderr)
   manifest=root/'java'/v/'icons/manifest.json'
   m=json.loads(manifest.read_text()) if manifest.exists() else {}
   entries={e['id']:e for e in m.get('entries',[])};e=entries.get('minecraft:'+selected,{});healthy=entries.get('minecraft:wheat',{});expected='MISSING_MODEL' if fault=='model' else 'MISSING_TEXTURE'
   diagnostics=e.get('diagnostics',[])
   codes=[d.get('code') for d in diagnostics]
   expected_codes=[expected,'MISSING_TEXTURE'] if pack and fault=='model' else [expected]
   record={'version':v,'fault':fault,'exitCode':run.returncode,'code':e.get('code'),'diagnosticCodes':codes,'pngFiles':len(list(root.rglob('*.png'))),'counts':m.get('counts'),'continuedHealthyItem':healthy.get('status')=='success','fallbackPngWritten':(manifest.parent/e.get('path','absent.png')).is_file()}
   record.update(qaAgentSha256=m.get('settings',{}).get('agentSha256'),sourceSha256=m.get('sourceSha256'),healthyPngIntegrity=False,healthyRgbaMatchesBaseline=None)
   candidates=set(a.cache.resolve().glob('worker-*'))-old_runs
   if pack:
    candidates={run for run in candidates if (run/'game/resourcepacks/mcicon-qa.zip').is_file() and hashlib.sha256((run/'game/resourcepacks/mcicon-qa.zip').read_bytes()).hexdigest()==hashlib.sha256(pack).hexdigest()}
   else:candidates={run for run in candidates if (run/'worker.log').is_file() and '[mcicon QA]' in (run/'worker.log').read_text(errors='replace')}
   logs=[run/'worker.log' for run in candidates if (run/'worker.log').is_file()]
   native_log='\n'.join(log.read_text(errors='replace') for log in logs);(root/'worker.log').write_text(native_log)
   record.update(selectedId='minecraft:'+selected,sourceUnchanged=source_hash==hashlib.sha256(client.read_bytes()).hexdigest(),resourcePackSha256=hashlib.sha256(pack).hexdigest() if pack else None,corruptResource=path,originalResourceSha256=original_hash,packLoaded=any('[mcicon QA] resource source: minecraft:'+path.removeprefix('assets/minecraft/')+' = ' in line and line.endswith('mcicon-qa.zip') for line in native_log.splitlines()) if pack else None)
   record['ownedWorkerLogs']=len(logs)
   if record['continuedHealthyItem']:
    try:
     data=(manifest.parent/healthy['path']).read_bytes();width,height,rgba=audit.rgba_png(data)
     record['healthyPngIntegrity']=hashlib.sha256(data).hexdigest()==healthy['sha256'] and width==height==64 and any(rgba[3::4]) and not any(rgba[i] or rgba[i+1] or rgba[i+2] for i in range(0,len(rgba),4) if not rgba[i+3])
     if a.compare:
      reference=a.compare.resolve()/'java'/v/'icons/manifest.json';baseline=json.loads(reference.read_text());entry=next(e for e in baseline['entries'] if e['id']=='minecraft:wheat')
      original=(reference.parent/entry['path']).read_bytes()
      record['healthyRgbaMatchesBaseline']=hashlib.sha256(original).hexdigest()==entry['sha256'] and audit.rgba_png(original)==(width,height,rgba) and entry['path']==healthy['path'] and baseline['sourceSha256']==m['sourceSha256']
    except Exception as error:record['healthyAuditError']=str(error)
   record['expectedCodes']=expected_codes
   record['passed']=run.returncode==3 and record['pngFiles']==1 and not record['fallbackPngWritten'] and record['continuedHealthyItem'] and record['healthyPngIntegrity'] and record['sourceUnchanged'] and (not pack or record['packLoaded'] and len(logs)==1) and (not a.compare or record['healthyRgbaMatchesBaseline']) and any(code==record['code'] or code in codes for code in expected_codes) and m.get('counts',{}).get('succeeded')==1 and m.get('counts',{}).get('failed')==1
   results.append(record);(out/'report.json').write_text(json.dumps({'productionAgentSha256':agent_hash,'syntheticFaultInjection':a.injection=='observer','officialInputsModified':False,'injection':a.injection,'harnessSourcesSha256':sources,'injectionScope':{'model':'選択したパックから破損モデルJSONを読み込む','texture':'選択したパックから破損PNGを読み込む','special-texture':'選択したパックから破損チェストPNGを読み込む'} if pack else {'model':'Resolved item model replaced with vanilla fallback','texture':'Consumed sprite observer input replaced with vanilla missing atlas sprite'},'results':results},ensure_ascii=False,indent=2)+'\n')
   print(v,fault,'PASS' if record['passed'] else 'FAIL',flush=True)
raise SystemExit(any(not r['passed'] for r in results))
