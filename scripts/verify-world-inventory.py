#!/usr/bin/env python3
"""1.21.4の通常ワールド・InventoryScreenを描画workerとは独立に撮影する。"""
import argparse,hashlib,importlib.util,json,re,subprocess,uuid,zipfile
from pathlib import Path

repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--out',type=Path,required=True)
p.add_argument('--cache',type=Path,default=repo/'.local/cache')
p.add_argument('--compare',type=Path,required=True,help='1.21.4の64px出力を含むディレクトリ')
p.add_argument('--ids',nargs='+',default=['stone','diamond','chest','ender_chest','white_bed','white_banner','shield','player_head','dragon_head','white_shulker_box','glass','white_stained_glass','ice','grass_block','oak_leaves','potion','creeper_spawn_egg','leather_chestplate'])
p.add_argument('--docker-context',default='colima-mcicon')
p.add_argument('--docker-image',default='mcicon-render:dev')
a=p.parse_args()
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cache=a.cache.resolve();version=cache/'versions/1.21.4';meta=json.loads((version/'version.json').read_text())
ids=['minecraft:'+i if ':' not in i else i for i in a.ids]
if len(ids)!=len(set(ids)):p.error('IDは重複できません')
manifest=a.compare.resolve()/'java/1.21.4/icons/manifest.json';baseline=json.loads(manifest.read_text())
entries={e['id']:e for e in baseline['entries']}
if baseline['context'].get('lightmapFullBrightRgb')!=[252,252,252]:p.error('実インベントリ照明を反映した1.21.4のPNGを再生成してください')
if baseline['settings']['size']!=64 or any(i not in entries or entries[i]['status']!='success' for i in ids):p.error('比較元に64pxの成功画像が必要です')
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
client=version/'client.jar';source_hash=sha(client)
if source_hash!=baseline['sourceSha256']:p.error('比較元のclientとcacheが異なります')
worker=repo/'cli/build/install/mcicon/worker/worker-agent.jar'
classes=out/'classes';classes.mkdir()
subprocess.run(['javac','--release','8','-cp',str(worker),'-d',str(classes),str(repo/'scripts/qa/WorldInventoryAgent.java')],check=True)
agent=out/'world-reference.jar'
# reflection・ASM・JSONのみを同梱し、production rendererと固定化hookを実行環境から除く。
with zipfile.ZipFile(worker) as source,zipfile.ZipFile(agent,'w',compression=zipfile.ZIP_DEFLATED) as target:
 for e in source.infolist():
  if e.filename.startswith(('com/google/gson/','io/github/rs0325/mcicon/worker/shaded/asm/','io/github/rs0325/mcicon/worker/Symbols')):target.writestr(e.filename,source.read(e.filename))
 for f in classes.rglob('*.class'):target.write(f,f.relative_to(classes).as_posix())
 target.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.WorldInventoryAgent\r\n\r\n')
with zipfile.ZipFile(agent) as archive:
 excluded=['RenderAgent','CanonicalTransformer','CanonicalContext','ResourceTransformer','ModernRenderer','PngOutput','Bridge']
 assert not any('io/github/rs0325/mcicon/worker/'+name+'.class' in archive.namelist() for name in excluded)
game=out/'game';game.mkdir()
(game/'options.txt').write_text('soundCategory_master:0.0\nguiScale:4\nmaxFps:30\nfullscreen:false\nonboardAccessibility:false\nrenderDistance:2\nsimulationDistance:5\nclouds:false\n')
(out/'agent.json').write_text(json.dumps({'mappings':'/mcicon/cache/versions/1.21.4/mappings.txt','output':'/mcicon/qa','ids':ids}))
libraries=[]
for lib in meta['libraries']:
 allowed=not lib.get('rules')
 for rule in lib.get('rules',[]):
  os=rule.get('os',{})
  if os.get('name','linux')!='linux' or not re.fullmatch(os.get('arch','amd64'),'amd64') or rule.get('features'):continue
  allowed=rule['action']=='allow'
 if allowed and 'artifact' in lib.get('downloads',{}):
  ref=lib['downloads']['artifact'];path=cache/'libraries'/ref['path']
  if hashlib.sha1(path.read_bytes()).hexdigest()!=ref['sha1']:raise ValueError('library hash不一致: '+ref['path'])
  libraries.append('/mcicon/cache/libraries/'+ref['path'])
image=subprocess.run(['docker','--context',a.docker_context,'image','inspect',a.docker_image,'--format','{{.Id}}'],capture_output=True,text=True,check=True).stdout.strip()
active=subprocess.run(['docker','--context',a.docker_context,'ps','--format','{{.Names}}'],capture_output=True,text=True,check=True).stdout.splitlines()
if sum(n.startswith(('mcicon-worker-','mcicon-world-qa-')) for n in active)>=2:raise RuntimeError('native clientの同時実行は2つまでです')
container='mcicon-world-qa-'+str(uuid.uuid4())
command=['docker','--context',a.docker_context,'run','--rm','--name',container,'--platform','linux/amd64','--network','none','--memory','3g','--cpus','4','-v',str(cache)+':/mcicon/cache:ro','-v',str(out)+':/mcicon/qa',image,'/opt/java/java21/bin/java','-Xmx2G','-Djava.awt.headless=true','-Duser.timezone=UTC','-Djava.library.path=/mcicon/cache/versions/1.21.4/natives-linux','-javaagent:/mcicon/qa/world-reference.jar=/mcicon/qa/agent.json','-cp',':'.join(['/mcicon/cache/versions/1.21.4/client.jar']+libraries),meta['mainClass'],'--username','MCIconGenerator','--version','1.21.4','--gameDir','/mcicon/qa/game','--assetsDir','/mcicon/cache/assets','--assetIndex',meta['assetIndex']['id'],'--accessToken','0','--uuid','00000000000000000000000000000000','--userType','legacy','--width','1280','--height','960','--disableMultiplayer']
report={'scope':'通常のローカルflatワールドのInventoryScreen。QA callbackはMinecraft.runTick末尾のみで、ゲーム状態・画面操作とnative Screenshotを使用する。描画メソッド、production初期化、canonical hook、透過復元を共有しない。完全な無計装clientではない。','version':'1.21.4','sourceSha256':source_hash,'baselineAgentSha256':baseline['settings']['agentSha256'],'qaAgentSha256':sha(agent),'imageId':image,'harnessSourcesSha256':{str(f.relative_to(repo)):sha(f) for f in [Path(__file__).resolve(),repo/'scripts/qa/WorldInventoryAgent.java']},'windowPixels':[1280,960],'guiScale':4,'worldPreset':'flat','seed':'0','slot':9,'excludedProductionClasses':excluded,'maxAllowedChannelError':2,'baselineFullBrightRgb':baseline['context']['lightmapFullBrightRgb'],'entries':[],'passed':False}
save=lambda:(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
save()
try:
 with (out/'client.log').open('w') as log:run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=300)
 report['exitCode']=run.returncode
except (subprocess.TimeoutExpired,KeyboardInterrupt) as error:
 subprocess.run(['docker','--context',a.docker_context,'stop','--time','5',container],capture_output=True)
 report['timedOut']=isinstance(error,subprocess.TimeoutExpired);report['interrupted']=isinstance(error,KeyboardInterrupt);save();raise
save()
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
capture=json.loads((out/'capture.json').read_text());report['capture']=capture
def slot(c):
 w,h,pixels=audit.rgba_png((out/c['screen']).read_bytes())
 if (w,h)!=(1280,960):raise ValueError('画面サイズが不一致です')
 x,y=c['xGui']*4,c['yGui']*4
 return b''.join(pixels[(row*w+x)*4:(row*w+x+64)*4] for row in range(y,y+64))
empty=capture['captures'][0]
if empty['id']!='empty' or empty['count']!=0:raise ValueError('空スロットの基準が必要です')
background=slot(empty)
for c in capture['captures'][1:]:
 e=entries[c['id']];path=manifest.parent/e['path'];assert sha(path)==e['sha256']
 w,h,rgba=audit.rgba_png(path.read_bytes());assert (w,h)==(64,64)
 native=slot(c);errors=[];bad=0
 light=[(c['fullBrightArgb']>>shift)&255 for shift in (16,8,0)]
 for i in range(0,len(rgba),4):
  delta=[abs((rgba[i+k]*rgba[i+3]+background[i+k]*(255-rgba[i+3])+127)//255-native[i+k]) for k in range(3)]
  errors.extend(delta);bad+=max(delta)>2
 opaque=all(native[i]==255 and background[i]==255 for i in range(3,len(native),4))
 entry={'id':c['id'],'exportPngSha256':e['sha256'],'screenSha256':sha(out/c['screen']),'maxChannelError':max(errors),'meanChannelError':sum(errors)/len(errors),'pixelsOverTolerance':bad,'count':c['count'],'fullBrightRgb':light,'opaqueReference':opaque,'rawPassed':bad==0 and c['count']==1 and opaque,'lightmapMatches':light==report['baselineFullBrightRgb']}
 report['entries'].append(entry);print(c['id'],'PASS' if entry['rawPassed'] and entry['lightmapMatches'] else 'FAIL',max(errors),flush=True)
report['officialInputsModified']=sha(client)!=source_hash
complete=run.returncode==0 and capture.get('completed',False) and len(report['entries'])==len(ids) and not report['officialInputsModified']
report['passed']=complete and all(e['rawPassed'] and e['lightmapMatches'] for e in report['entries'])
save();raise SystemExit(not report['passed'])
