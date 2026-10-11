#!/usr/bin/env python3
"""1.21.4の既知のRGBAテクスチャを通常のモデル・shader経由で検証する。"""
import argparse,hashlib,importlib.util,io,json,math,shutil,struct,subprocess,tempfile,zipfile,zlib
from pathlib import Path

repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--out',type=Path,required=True)
p.add_argument('--cache',type=Path,default=repo/'.local/cache')
p.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
p.add_argument('--docker-context',default='colima-mcicon')
p.add_argument('--docker-image',default='mcicon-render:dev')
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('audit',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
alpha=[0,1,16,25,26,32,64,96,127,128,160,192,224,254,255,255]
colors=[(0,0,0),(255,255,255),(255,0,0),(0,255,0),(0,0,255),(128,128,128),(32,64,128),(200,50,150),(17,83,219),(240,180,60),(64,192,128),(127,128,129),(1,2,3),(254,253,252),(20,200,240),(220,40,80)]
base=[(*colors[y],alpha[x]) for y in range(16) for x in range(16)]
overlay=[(*colors[15-y],alpha[15-x]) for y in range(16) for x in range(16)]
lightmap=[252,252,252]

def sha(data):return hashlib.sha256(data).hexdigest()
def png(pixels):
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
 rows=b''.join(b'\0'+bytes(c for pixel in pixels[y*16:(y+1)*16] for c in pixel) for y in range(16))
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,6,0,0,0))+chunk(b'sRGB',b'\0')+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')

client=a.cache.resolve()/'versions/1.21.4/client.jar';source_hash=sha(client.read_bytes())
with zipfile.ZipFile(client) as z:
 version=json.loads(z.read('version.json'));shader=z.read('assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh')
 if 'if (color.a < 0.1)' not in shader.decode():raise ValueError('検証対象のalpha cutout条件が変わっています')
 pack_format=version['pack_version']['resource']
pack=out/'calibration-pack.zip'
with zipfile.ZipFile(pack,'w',compression=zipfile.ZIP_DEFLATED) as z:
 z.writestr('pack.mcmeta',json.dumps({'pack':{'pack_format':pack_format,'description':'MCIconGenerator QA: 既知のRGBA'}}))
 z.writestr('assets/minecraft/textures/item/diamond.png',png(base))
 z.writestr('assets/minecraft/textures/item/emerald.png',png(overlay))
 for item,textures in [('diamond',{'layer0':'minecraft:item/diamond'}),('emerald',{'layer0':'minecraft:item/diamond','layer1':'minecraft:item/emerald'})]:
  z.writestr('assets/minecraft/items/'+item+'.json',json.dumps({'model':{'type':'minecraft:model','model':'minecraft:item/'+item}}))
  z.writestr('assets/minecraft/models/item/'+item+'.json',json.dumps({'parent':'minecraft:item/generated','textures':textures}))

report={'scope':'1.21.4のQAパックで静的16px generated-modelの1層・2層を通常shaderで描画する。数値oracleはゲームクラス・GPU・PngOutputを使用しない。通常GUI比較はfixed-v1 hookとbootstrapを共有する。バニラ全モデルやlinear-light合成の受入ではない。','syntheticResources':True,'officialInputsModified':False,'sourceSha256':source_hash,'resourcePackSha256':sha(pack.read_bytes()),'shaderSha256':sha(shader),'sourceAlpha':alpha,'sourceColors':colors,'fullBrightLightmapRgb':lightmap,'shaderAlphaCutoff':0.1,'comparisonEncoding':'byte RGBのsource-over。sRGB EOTFで線形化した合成とは区別する。','harnessSourcesSha256':{str(f.relative_to(repo)):sha(f.read_bytes()) for f in [Path(__file__).resolve(),repo/'scripts/qa/ResourcePackFaultAgent.java']},'oracleResults':[]}
with tempfile.TemporaryDirectory(prefix='.cli-snapshot-',dir=out) as temporary:
 distribution=Path(temporary)/'mcicon';shutil.copytree(a.cli.resolve().parent.parent,distribution)
 agent=distribution/'worker/worker-agent.jar';report['productionAgentSha256']=sha(agent.read_bytes())
 classes=out/'classes';classes.mkdir()
 subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(repo/'scripts/qa/ResourcePackFaultAgent.java')],check=True)
 injected=out/'calibration-worker.jar'
 with zipfile.ZipFile(agent) as source,zipfile.ZipFile(injected,'w',compression=zipfile.ZIP_DEFLATED) as target:
  for e in source.infolist():target.writestr(e.filename,b'Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.ResourcePackFaultAgent\r\n\r\n' if e.filename=='META-INF/MANIFEST.MF' else source.read(e.filename))
  for f in classes.rglob('*.class'):target.write(f,f.relative_to(classes).as_posix())
  target.write(pack,'mcicon-resource-pack.zip')
  target.writestr('mcicon-resource-pack-options.txt','\nresourcePacks:["vanilla","file/mcicon-qa.zip"]\nincompatibleResourcePacks:[]\n')
  target.writestr('mcicon-resource-path.txt','minecraft:textures/item/diamond.png')
 active=subprocess.run(['docker','--context',a.docker_context,'ps','--format','{{.Names}}'],capture_output=True,text=True,check=True).stdout.splitlines()
 if sum(n.startswith('mcicon-worker-') for n in active)>1:raise RuntimeError('native worker上限2つまで空きを待ってください')
 old_runs=set(a.cache.resolve().glob('worker-*'));folder=out/'export'
 command=[str(distribution/'bin/mcicon'),'render','--version','1.21.4','--cache',str(a.cache.resolve()),'--offline','--size','64','--allow-experimental','--out',str(folder),'--worker-jar',str(injected),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--id','diamond','--id','emerald','--id','wheat','--json','--debug']
 with (out/'result.json').open('w') as stdout,(out/'cli.log').open('w') as stderr:run=subprocess.run(command,stdout=stdout,stderr=stderr)
 report['exitCode']=run.returncode
 runs=[r for r in set(a.cache.resolve().glob('worker-*'))-old_runs if (r/'game/resourcepacks/mcicon-qa.zip').is_file() and sha((r/'game/resourcepacks/mcicon-qa.zip').read_bytes())==report['resourcePackSha256']]
 report['ownedWorkerLogs']=len(runs);log='\n'.join((r/'worker.log').read_text(errors='replace') for r in runs);(out/'worker.log').write_text(log)
 report['packSourceConfirmed']=len(runs)==1 and any('[mcicon QA] resource source: minecraft:textures/item/diamond.png = ' in line and line.endswith('mcicon-qa.zip') for line in log.splitlines())
 manifest=folder/'java/1.21.4/icons/manifest.json';m=json.loads(manifest.read_text());report['qaAgentSha256']=m['settings']['agentSha256'];report['imageId']=m['settings']['dockerImageId'];report['exportAudit']=audit.audit(manifest,require_all=False)
 entries={e['id']:e for e in m['entries']}
 report['manifestFullBrightRgb']=m['context']['lightmapFullBrightRgb']
 if report['manifestFullBrightRgb']!=lightmap:raise ValueError('manifestの照明が数値oracleと不一致です')
 for item,layers in [('diamond',[base]),('emerald',[base,overlay])]:
  e=entries['minecraft:'+item];data=(manifest.parent/e['path']).read_bytes();w,h,rgba=audit.rgba_png(data)
  assert w==h==64 and sha(data)==e['sha256']
  max_alpha=0;max_rgb=0;max_weighted=0;bad=0;discarded=0
  for y in range(64):
   for x in range(64):
    expected_alpha=0.;premult=[0.,0.,0.]
    for layer in layers:
     pixel=layer[(y//4)*16+x//4];opacity=pixel[3]/255 if pixel[3]/255>=0.1 else 0
     premult=[pixel[c]*lightmap[c]/255*opacity+premult[c]*(1-opacity) for c in range(3)];expected_alpha=opacity+expected_alpha*(1-opacity)
    offset=(y*64+x)*4;actual=rgba[offset:offset+4];da=abs(actual[3]-expected_alpha*255);max_alpha=max(max_alpha,da)
    weighted=max(abs(actual[c]*actual[3]/255-premult[c]) for c in range(3));max_weighted=max(max_weighted,weighted)
    if expected_alpha:
     rgb=max(abs(actual[c]-premult[c]/expected_alpha) for c in range(3));max_rgb=max(max_rgb,rgb)
     # 黒白の各8bit量子化とalphaの1段階差をstraight RGBへ戻す際の上限。
     invalid=da>1.01 or weighted>2 or rgb>math.ceil(1/expected_alpha)+1
    else:
     discarded+=1;invalid=any(actual)
    bad+=bool(invalid)
  report['oracleResults'].append({'id':'minecraft:'+item,'layers':len(layers),'exportPngSha256':e['sha256'],'maxAlphaError':max_alpha,'maxRgbError':max_rgb,'maxPremultipliedRgbError':max_weighted,'discardedPixels':discarded,'pixelsOverQuantizationBound':bad,'passed':bad==0})
 report['sourceUnchanged']=source_hash==sha(client.read_bytes());report['oraclePassed']=run.returncode==0 and report['packSourceConfirmed'] and report['sourceUnchanged'] and not report['exportAudit']['failures'] and all(x['passed'] for x in report['oracleResults'])
 report['passed']=False
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print('数値oracle',report['oracleResults'],flush=True)
 if not report['oraclePassed']:raise SystemExit(1)
 command=['python3',str(repo/'scripts/verify-gui-reference.py'),'--versions','1.21.4','--framebuffer-probe','--resource-pack',str(pack),'--cache',str(a.cache.resolve()),'--cli',str(distribution/'bin/mcicon'),'--ids','diamond','emerald','wheat','--backgrounds','000000','ffffff','7f7f7f','206080','c02040','20c040','--out',str(out/'gui'),'--compare',str(folder),'--docker-context',a.docker_context,'--docker-image',a.docker_image]
 report['guiExitCode']=subprocess.call(command)
 if (out/'gui/report.json').is_file():report['guiReport']=json.loads((out/'gui/report.json').read_text())
 report['passed']=report['oraclePassed'] and report['guiExitCode']==0 and len(report.get('guiReport',{}).get('jobs',[]))==6 and all(j['passed'] for j in report['guiReport']['jobs'])
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 raise SystemExit(0 if report['passed'] else 1)
