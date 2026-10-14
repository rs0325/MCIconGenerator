#!/usr/bin/env python3
"""実readback後の黒白pass例外と、所有資源解放・後続PNGを検証する。"""
import argparse,hashlib,importlib.util,json,shutil,subprocess,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--cache',type=Path,required=True)
p.add_argument('--versions',nargs='+',choices=['1.21.4','26.3'],default=['1.21.4','26.3']);p.add_argument('--faults',nargs='+',choices=['black','white'],default=['black','white'])
p.add_argument('--docker-context',default='colima-mcicon');p.add_argument('--docker-image',default='mcicon-render:0.1.0-preview.1')
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
dist=out/'distribution';shutil.copytree(repo/'cli/build/install/mcicon',dist);agent=dist/'worker/worker-agent.jar';classes=out/'classes';classes.mkdir()
subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(repo/'scripts/qa/NativeFailureAgent.java')],check=True)
spec=importlib.util.spec_from_file_location('audit',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
report={'productionAgentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),'cliCoreSha256':hashlib.sha256(next((dist/'lib').glob('core-*.jar')).read_bytes()).hexdigest(),'sourcesSha256':{str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(),repo/'scripts/qa/NativeFailureAgent.java']},'results':[]}
for version in a.versions:
 normal=None
 for fault in ['normal']+a.faults:
  worker=agent
  if fault!='normal':
   worker=out/(fault+'-worker.jar')
   with zipfile.ZipFile(agent) as source,zipfile.ZipFile(worker,'w',compression=zipfile.ZIP_DEFLATED) as target:
    for entry in source.infolist():
     data=source.read(entry.filename)
     if entry.filename=='META-INF/MANIFEST.MF':data=b'Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.NativeFailureAgent\r\n\r\n'
     target.writestr(entry.filename,data)
    for compiled in classes.rglob('*.class'):target.write(compiled,compiled.relative_to(classes).as_posix())
    target.writestr('mcicon-native-fault.txt',fault)
  root=out/fault/version;root.mkdir(parents=True);before=set(a.cache.glob('worker-*'))
  cmd=[str(dist/'bin/mcicon'),'render','--version',version,'--cache',str(a.cache.resolve()),'--offline','--allow-experimental','--out',str(root),'--worker-jar',str(worker),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--id','stone','--id','wheat','--debug','--json']
  with (root/'result.json').open('w') as stdout,(root/'cli.log').open('w') as stderr:run=subprocess.run(cmd,stdout=stdout,stderr=stderr)
  workers=set(a.cache.glob('worker-*'))-before
  if len(workers)!=1:raise RuntimeError('所有workerを特定できません')
  native=(workers.pop()/'worker.log').read_text(errors='replace');(root/'worker.log').write_text(native)
  manifest=root/'java'/version/'icons/manifest.json';m=json.loads(manifest.read_text()) if manifest.exists() else {}
  entries={e['id']:e for e in m.get('entries',[])};passes=[json.loads(l.split('[mcicon native pass] ',1)[1]) for l in native.splitlines() if '[mcicon native pass] ' in l]
  record={'version':version,'fault':fault,'exitCode':run.returncode,'qaAgentSha256':hashlib.sha256(worker.read_bytes()).hexdigest(),'manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest() if manifest.exists() else None,'workerLogSha256':hashlib.sha256(native.encode()).hexdigest(),'counts':m.get('counts'),'entries':entries,'passes':passes}
  if fault=='normal':
   record['passed']=run.returncode==0 and all(e.get('status')=='success' for e in entries.values());normal=manifest
  else:
   target=entries.get('minecraft:stone',{});healthy=entries.get('minecraft:wheat',{});baseline=json.load(normal.open());expected=next(e for e in baseline['entries'] if e['id']=='minecraft:stone')['path'];record['failureExpectedPath']=expected;record['failurePngWritten']=(manifest.parent/expected).is_file();record['pngFiles']=len(list(manifest.parent.rglob('*.png')));record['healthyRgbaEqual']=False
   if healthy.get('status')=='success' and normal:
    baseline=json.load(normal.open());reference=next(e for e in baseline['entries'] if e['id']=='minecraft:wheat');record['healthyRgbaEqual']=audit.rgba_png((manifest.parent/healthy['path']).read_bytes())==audit.rgba_png((normal.parent/reference['path']).read_bytes())
   record['injectedPasses']=sum(p.get('injected',False) for p in passes)
   record['stateRestored']=all({k:v for k,v in p['before'].items() if not k.startswith('cached')}=={k:v for k,v in p['after'].items() if not k.startswith('cached')} and p.get('officialCacheSynchronized') and p.get('officialBindZeroRestored') for p in passes)
   record['nativeOwnedReadback']=all(p.get('nativeReadbackCompleted') and p.get('bufferDirect') and p.get('bufferBytes')==64*64*4 and (p.get('framebufferLiveAtReadback') and p.get('targetFramebuffer')==p.get('boundReadFramebuffer') if version=='1.21.4' else p.get('copyUsesOwnedTargetAndBuffer') and p.get('submitted') and p.get('fenceCompleted')) for p in passes)
   record['resourcesReleased']=all(p.get('targetDestroyed') and p.get('colorReleased') and p.get('depthReleased') and (p.get('framebufferReleased') if version=='1.21.4' else p.get('bufferClosed') and p.get('mappingClosed') and p.get('fenceClosed') and p.get('guiEnded') and p.get('uniformsReset') and p.get('uniformBlocksReset') and p.get('atlasEndCalls',0)>0) for p in passes)
   record['passed']=run.returncode==3 and target.get('code')=='RENDER_FAILED' and 'MCIcon QA: native readback failure ('+fault+')' in target.get('message','') and not record['failurePngWritten'] and record['pngFiles']==1 and record['healthyRgbaEqual'] and record['injectedPasses']==1 and record['stateRestored'] and record['resourcesReleased'] and record['nativeOwnedReadback']
  report['results'].append(record);(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(version,fault,'PASS' if record['passed'] else 'FAIL',flush=True)
raise SystemExit(any(not r['passed'] for r in report['results']))
