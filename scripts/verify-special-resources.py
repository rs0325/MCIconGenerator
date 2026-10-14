#!/usr/bin/env python3
"""特殊rendererの実resource取得と既存欠損観測を、通常PNGとの比較付きで監査する。"""
import argparse,hashlib,importlib.util,json,shutil,subprocess,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--cache',type=Path,required=True)
p.add_argument('--versions',nargs='+',choices=['1.13','1.21.4','26.3'],default=['1.13','1.21.4','26.3'])
p.add_argument('--docker-context',default='colima-mcicon');p.add_argument('--docker-image',default='mcicon-render:0.1.0-preview.1')
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
dist=out/'distribution';shutil.copytree(repo/'cli/build/install/mcicon',dist)
agent=dist/'worker/worker-agent.jar';classes=out/'classes';classes.mkdir()
subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(repo/'scripts/qa/SpecialResourceAgent.java')],check=True)
injected=out/'audit-worker.jar'
with zipfile.ZipFile(agent) as source,zipfile.ZipFile(injected,'w',compression=zipfile.ZIP_DEFLATED) as target:
 for entry in source.infolist():
  data=source.read(entry.filename)
  if entry.filename=='META-INF/MANIFEST.MF':data=b'Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.SpecialResourceAgent\r\n\r\n'
  target.writestr(entry.filename,data)
 for compiled in classes.rglob('*.class'):target.write(compiled,compiled.relative_to(classes).as_posix())
spec=importlib.util.spec_from_file_location('audit',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
ids=['chest','dragon_head','player_head','shield'];results=[]
report={'productionAgentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),'cliCoreSha256':hashlib.sha256(next((dist/'lib').glob('core-*.jar')).read_bytes()).hexdigest(),'qaAgentSha256':hashlib.sha256(injected.read_bytes()).hexdigest(),'sourcesSha256':{str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(),repo/'scripts/qa/SpecialResourceAgent.java']},'results':results}
for version in a.versions:
 record={'version':version,'runs':{}};results.append(record)
 for mode,worker in [('normal',agent),('audit',injected)]:
  root=out/mode/version;root.mkdir(parents=True);before=set(a.cache.glob('worker-*'))
  cmd=[str(dist/'bin/mcicon'),'render','--version',version,'--cache',str(a.cache.resolve()),'--offline','--allow-experimental','--out',str(root),'--worker-jar',str(worker),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--json']
  for item in ids:cmd.extend(['--id',item])
  with (root/'result.json').open('w') as stdout,(root/'cli.log').open('w') as stderr:run=subprocess.run(cmd,stdout=stdout,stderr=stderr)
  workers=set(a.cache.glob('worker-*'))-before
  if len(workers)>1 or run.returncode==0 and len(workers)!=1:raise RuntimeError('所有するworkerを特定できません')
  native=(workers.pop()/'worker.log').read_text(errors='replace') if workers else '';(root/'worker.log').write_text(native)
  manifest=root/'java'/version/'icons/manifest.json';m=json.loads(manifest.read_text()) if manifest.exists() else {}
  record['runs'][mode]={'exitCode':run.returncode,'manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest() if manifest.exists() else None,'workerLogSha256':hashlib.sha256(native.encode()).hexdigest(),'counts':m.get('counts'),'entries':m.get('entries')}
  if mode=='audit':
   record['passes']=[json.loads(l.split('[mcicon special pass] ',1)[1]) for l in native.splitlines() if '[mcicon special pass] ' in l]
   record['resourceRequests']=sorted(set(l.split('[mcicon special resource] ',1)[1] for l in native.splitlines() if '[mcicon special resource] ' in l))
   record['hooks']=sorted(set(l.split('[mcicon special hook] ',1)[1] for l in native.splitlines() if '[mcicon special hook] ' in l))
  if run.returncode!=0:break
 record['rgbaComparisons']=[]
 if len(record['runs'])==2 and all(r['exitCode']==0 for r in record['runs'].values()):
  for item in ids:
   images=[]
   for mode in ['normal','audit']:
    folder=out/mode/version/'java'/version/'icons';m=json.loads((folder/'manifest.json').read_text());entry=next(e for e in m['entries'] if e['id']=='minecraft:'+item)
    data=(folder/entry['path']).read_bytes();image=audit.rgba_png(data)
    if entry['status']!='success' or hashlib.sha256(data).hexdigest()!=entry['sha256'] or image[:2]!=(64,64) or not any(image[2][3::4]):raise RuntimeError('正常PNGのhash・サイズ・可視画素が不正です')
    images.append(image)
   record['rgbaComparisons'].append({'id':'minecraft:'+item,'equal':images[0]==images[1]})
 record['observations']=[]
 for item in record.get('passes',[]):
  name=item['id'].split(':')[1];events=item['events'];direct=version=='1.13' or name in ['dragon_head','player_head']
  path={'chest':'textures/entity/chest/normal.png','dragon_head':'textures/entity/enderdragon/dragon.png','player_head':'textures/entity/steve.png' if version=='1.13' else 'textures/entity/player/slim/steve.png','shield':'textures/entity/shield_base_nopattern.png'}[name]
  if not direct:path='entity/chest/normal' if name=='chest' else 'entity/shield/shield_base_nopattern' if version=='26.3' else 'entity/shield_base_nopattern'
  resource='minecraft:'+path
  observed=any(key.startswith('guard:'+('texture' if direct else 'sprite')) and 'resource='+resource in key for key in events)
  cache=any(key.startswith(('bind ' if version=='1.13' else 'get ')) and 'cache=true' in key and (resource in key if direct else 'textures/atlas/'+('chest.png' if name=='chest' else 'shield_patterns.png') in key) for key in events)
  gpu=version!='26.3' or any(key.startswith('gpu Sampler0 ') and 'resource='+('minecraft:'+('textures/atlas/chest.png' if name=='chest' else 'textures/atlas/shield_patterns.png') if not direct else resource) in key for key in events)
  record['observations'].append({'id':item['id'],'consumedResource':resource,'direct':direct,'existingGuardReached':observed,'cacheHitInsidePass':cache,'gpuUniformCorrelated':gpu if version=='26.3' else None,'passed':observed and cache and gpu})
 record['passed']=all(x['passed'] for x in record['observations']) and len(record['observations'])==4 and len(record['runs'])==2 and all(r['exitCode']==0 for r in record['runs'].values()) and len(record.get('passes',[]))==4 and len(record['rgbaComparisons'])==4 and all(r['equal'] for r in record['rgbaComparisons'])
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(version,'PASS' if record['passed'] else 'FAIL',flush=True)
raise SystemExit(any(not r['passed'] for r in results))
