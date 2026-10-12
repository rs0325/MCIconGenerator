#!/usr/bin/env python3
"""Compare exported icons recomposited in byte RGB with a normal native GUI frame.

The QA title-screen fixture uses native GUI projection, lighting and Screenshot;
it shares fixed-v1 hooks and registry bootstrap, not WorkerRenderer.render or
PngOutput. This is not an uninstrumented in-world inventory screenshot.
"""
import argparse,hashlib,importlib.util,json,shutil,subprocess,tempfile,uuid,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
releases=[v['id'] for v in json.loads((repo/'adapters/releases.json').read_text())['versions']]
reference_versions=releases[releases.index('1.21.4'):]+['26.3']
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True)
p.add_argument('--versions',nargs='+',choices=reference_versions,default=['1.13','1.21.4','26.3'])
p.add_argument('--cache',type=Path,default=repo/'.local/cache')
p.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
p.add_argument('--compare',type=Path,required=True,help='64px transparent full-export baseline')
p.add_argument('--ids',nargs='+',default=['stone','diamond','chest','ender_chest','white_bed','white_banner','shield','player_head','white_shulker_box','glass','white_stained_glass','ice','grass_block','oak_leaves','potion','creeper_spawn_egg','leather_chestplate','clock','compass','enchanted_book'])
p.add_argument('--backgrounds',nargs='+',default=['7f7f7f','206080'])
p.add_argument('--position',nargs=2,type=int,default=[32,32],metavar=('X','Y'))
p.add_argument('--resource-pack',type=Path,help='1.21.4の色・alpha検証用QAパック')
p.add_argument('--framebuffer-probe',action='store_true',help='1.21.4の通常GUIでframebufferの色形式を観測する')
p.add_argument('--docker-context',default='colima-mcicon');p.add_argument('--docker-image',default='mcicon-render:dev')
a=p.parse_args()
if (a.resource_pack or a.framebuffer_probe) and a.versions!=['1.21.4']:p.error('色形式・QAパックの比較は1.21.4専用です')
if len(a.versions)!=len(set(a.versions)) or len(a.backgrounds)!=len(set(a.backgrounds)):p.error('Use unique versions/backgrounds')
if any(not 0<=v<=224 for v in a.position):p.error('Position must keep the slot inside the native window')
try:colors=[int(v,16) for v in a.backgrounds]
except ValueError:p.error('Backgrounds must be six-digit RGB hex')
if any(len(v)!=6 or not 0<=c<=0xffffff for v,c in zip(a.backgrounds,colors)):p.error('Backgrounds must be six-digit RGB hex')
ids=['minecraft:'+v if ':' not in v else v for v in a.ids]
if len(ids)!=len(set(ids)):p.error('Use unique IDs')
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
results=[];report={'scope':'Instrumented title-screen fixture in normal native GUI frames; shared fixed-v1 context and registry initialization, independent export target/projection/composition. Not uninstrumented world screenshots or final semantic acceptance.','officialInputsModified':False,'size':64,'positionGui':a.position,'windowPixels':[1280,960],'guiScale':4,'legacyInventoryState':{'depthTest':True,'cullFace':True,'lighting':'Lighting.turnOnGui'},'maxAllowedChannelError':2,'harnessSourcesSha256':{str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(),repo/'scripts/qa/GuiReferenceAgent.java',repo/'scripts/qa/ResourcePackFaultAgent.java']},'jobs':results}
with tempfile.TemporaryDirectory(prefix='.cli-snapshot-',dir=out) as temp:
 distribution=Path(temp)/'mcicon';shutil.copytree(a.cli.resolve().parent.parent,distribution)
 report['cliCoreSha256']=hashlib.sha256(next((distribution/'lib').glob('core-*.jar')).read_bytes()).hexdigest()
 agent=distribution/'worker/worker-agent.jar';report['productionAgentSha256']=hashlib.sha256(agent.read_bytes()).hexdigest()
 classes=out/'classes';classes.mkdir();subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(repo/'scripts/qa/GuiReferenceAgent.java'),str(repo/'scripts/qa/ResourcePackFaultAgent.java')],check=True)
 for version in a.versions:
  reference=a.compare.resolve()/'java'/version/'icons/manifest.json';baseline=json.loads(reference.read_text());entries={e['id']:e for e in baseline['entries']}
  if any(i not in entries or entries[i]['status']!='success' for i in ids):raise ValueError('Baseline lacks requested IDs')
  for name,color in zip(a.backgrounds,colors):
   active=subprocess.run(['docker','--context',a.docker_context,'ps','--format','{{.Names}}'],capture_output=True,text=True,check=True).stdout.splitlines()
   if sum(n.startswith('mcicon-worker-') for n in active)>1:raise RuntimeError('At most two native workers are allowed')
   capture=a.cache.resolve()/('qa-reference-'+str(uuid.uuid4()));capture.mkdir()
   config={'background':color,'x':a.position[0],'y':a.position[1],'captureFolder':'/mcicon/cache/'+capture.name}
   if a.framebuffer_probe:config['framebufferProbe']=True
   if a.resource_pack:config['resourcePack']=True
   injected=out/'reference-worker.jar'
   with zipfile.ZipFile(agent) as source,zipfile.ZipFile(injected,'w',compression=zipfile.ZIP_DEFLATED) as target:
    for e in source.infolist():target.writestr(e.filename,b'Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.GuiReferenceAgent\r\n\r\n' if e.filename=='META-INF/MANIFEST.MF' else source.read(e.filename))
    for f in classes.rglob('*.class'):target.write(f,f.relative_to(classes).as_posix())
    target.writestr('mcicon-gui-reference.json',json.dumps(config))
    if a.resource_pack:
     target.write(a.resource_pack,'mcicon-resource-pack.zip')
     target.writestr('mcicon-resource-pack-options.txt','\nresourcePacks:["vanilla","file/mcicon-qa.zip"]\nincompatibleResourcePacks:[]\n')
     target.writestr('mcicon-resource-path.txt','minecraft:textures/item/diamond.png')
   folder=out/version/name;folder.mkdir(parents=True)
   cmd=[str(distribution/'bin/mcicon'),'render','--version',version,'--cache',str(a.cache.resolve()),'--offline','--size','64','--allow-experimental','--out',str(folder),'--worker-jar',str(injected),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--json','--debug']
   for item in ids:cmd.extend(['--id',item])
   old_runs=set(a.cache.resolve().glob('worker-*'))
   with (folder/'result.json').open('w') as stdout,(folder/'cli.log').open('w') as stderr:run=subprocess.run(cmd,stdout=stdout,stderr=stderr)
   shutil.copytree(capture,folder/'screens');shutil.rmtree(capture)
   mpath=folder/'java'/version/'icons/manifest.json';m=json.loads(mpath.read_text()) if mpath.exists() else {}
   job={'version':version,'background':name,'exitCode':run.returncode,'qaAgentSha256':m.get('settings',{}).get('agentSha256'),'sourceSha256':m.get('sourceSha256'),'baselineAgentSha256':baseline['settings']['agentSha256'],'imageId':m.get('settings',{}).get('dockerImageId'),'entries':[]}
   if a.framebuffer_probe:
    state=json.loads((folder/'screens/framebuffer.json').read_text());job['framebuffer']=state
    job['framebufferValid']=state['framebuffer']!=0 and not state['srgbEnabled'] and state['colorEncoding']==0x2601 and state['glError']==0 and all(state[c+'Bits']==8 for c in ['red','green','blue','alpha'])
   if a.resource_pack:
    job['resourcePackSha256']=hashlib.sha256(a.resource_pack.read_bytes()).hexdigest()
    pack_runs=[run for run in set(a.cache.resolve().glob('worker-*'))-old_runs if (run/'game/resourcepacks/mcicon-qa.zip').is_file() and hashlib.sha256((run/'game/resourcepacks/mcicon-qa.zip').read_bytes()).hexdigest()==job['resourcePackSha256']]
    job['ownedPackWorkerLogs']=len(pack_runs)
    job['packSourceConfirmed']=len(pack_runs)==1 and any('[mcicon QA] resource source: minecraft:textures/item/diamond.png = ' in line and line.endswith('mcicon-qa.zip') for run in pack_runs for line in (run/'worker.log').read_text(errors='replace').splitlines())
   for e in m.get('entries',[]):
    entry={'id':e['id'],'status':e['status']}
    if e['status']=='success':
     original=(reference.parent/entries[e['id']]['path']).read_bytes();native=(mpath.parent/e['path']).read_bytes()
     w,h,rgba=audit.rgba_png(original);rw,rh,pixels=audit.rgba_png(native)
     assert w==h==rw==rh==64 and hashlib.sha256(original).hexdigest()==entries[e['id']]['sha256'] and hashlib.sha256(native).hexdigest()==e['sha256']
     bg=[(color>>16)&255,(color>>8)&255,color&255];errors=[];bad=0;alpha=0
     for i in range(0,len(rgba),4):
      delta=[abs((rgba[i+c]*rgba[i+3]+bg[c]*(255-rgba[i+3])+127)//255-pixels[i+c]) for c in range(3)]
      errors.extend(delta);bad+=max(delta)>2;alpha+=pixels[i+3]!=255
     screen=folder/'screens'/(e['id'].replace(':','_')+'-screen.png')
     entry.update(exportPngSha256=entries[e['id']]['sha256'],referenceCropSha256=e['sha256'],referenceScreenSha256=hashlib.sha256(screen.read_bytes()).hexdigest(),maxChannelError=max(errors),meanChannelError=sum(errors)/len(errors),pixelsOverTolerance=bad,nonOpaqueReferencePixels=alpha,sourceMatchesBaseline=m['sourceSha256']==baseline['sourceSha256'],passed=bad==0 and alpha==0 and m['sourceSha256']==baseline['sourceSha256'])
    else:entry['diagnostics']=e.get('diagnostics');entry['passed']=False
    job['entries'].append(entry)
   job['capturedScreens']=len(list((folder/'screens').glob('*-screen.png')))
   job['passed']=run.returncode==0 and len(job['entries'])==len(ids) and job['capturedScreens']==len(ids) and all(e['passed'] for e in job['entries']) and (not a.resource_pack or job['packSourceConfirmed']) and (not a.framebuffer_probe or job['framebufferValid'])
   results.append(job);(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
   print(version,name,'PASS' if job['passed'] else 'FAIL',[(e['id'],e.get('maxChannelError')) for e in job['entries']],flush=True)
raise SystemExit(any(not j['passed'] for j in results))
