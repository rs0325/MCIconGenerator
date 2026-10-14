#!/usr/bin/env python3
"""agent無しclientを外部入力で操作し、通常InventoryScreenをF2で撮影する。"""
import argparse,hashlib,importlib.util,json,re,shlex,shutil,subprocess,time,uuid
from pathlib import Path

repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--version',choices=['1.20.6','1.21.4'],required=True)
p.add_argument('--cache',type=Path,required=True)
p.add_argument('--world-save',type=Path,required=True,help='同版の保存済みflat・survival・Commands有効のワールド。元saveは変更しない')
p.add_argument('--out',type=Path,required=True)
p.add_argument('--ids',nargs='+')
p.add_argument('--qa-reference',type=Path,required=True,help='同版の受入済みWorld QA画像・report.json。emptyと静的IDを画素単位で照合する')
p.add_argument('--samples',type=int,choices=[1,2],default=1)
p.add_argument('--docker-context',default='colima-mcicon')
p.add_argument('--docker-image',default='mcicon-render:0.1.0-preview.1')
a=p.parse_args()
cache=a.cache.resolve();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
version=cache/'versions'/a.version;meta=json.loads((version/'version.json').read_text())
client=version/'client.jar'
if hashlib.sha1(client.read_bytes()).hexdigest()!=meta['downloads']['client']['sha1']:raise ValueError('client hashが不一致です')
ids=a.ids or (['stone','player_head','chest','glass','grass_block'] if a.version=='1.20.6' else ['diamond','clock','compass','recovery_compass','enchanted_book','sea_lantern'])
allowed=['stone','player_head','chest','glass','grass_block'] if a.version=='1.20.6' else ['diamond','clock','compass','recovery_compass','enchanted_book','sea_lantern']
if any(id not in allowed for id in ids):p.error('この版の受入対象IDを指定してください')
reference=a.qa_reference.resolve();qa=json.loads((reference/'report.json').read_text())
if qa['version']!=a.version or qa['sourceSha256']!=sha(client) or not qa['passed']:p.error('同じ元clientの受入済みWorld QAが必要です')
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
def crop(path):
 w,h,rgba=audit.rgba_png(path.read_bytes())
 if (w,h)!=(1280,960):raise ValueError('F2画像は1280×960である必要があります')
 return b''.join(rgba[(y*w+320)*4:(y*w+384)*4]for y in range(484,548))
qa_images={'empty':reference/'empty-screen.png'}
for id in ids:
 if id not in ['clock','compass','recovery_compass','enchanted_book','sea_lantern']:qa_images[id]=reference/('minecraft_'+id+'-screen.png')
qa_pixels={id:crop(path)for id,path in qa_images.items()}
if len(ids)!=len(set(ids)) or any(not re.fullmatch('[a-z0-9_]+',id) for id in ids):p.error('IDは重複なしのvanilla名を指定してください')
world=a.world_save.resolve();world_inputs=[{'path':str(f.relative_to(world)),'sha256':sha(f)} for f in sorted(world.rglob('*')) if f.is_file()]
game=out/'game';(game/'saves').mkdir(parents=True);shutil.copytree(world,game/'saves'/'MCIcon QA')
(game/'options.txt').write_text('soundCategory_master:0.0\nguiScale:4\nmaxFps:30\nfullscreen:false\nonboardAccessibility:false\nrenderDistance:2\nsimulationDistance:5\nclouds:false\nlang:en_us\npauseOnLostFocus:false\n')
classes=out/'controller-classes';classes.mkdir()
subprocess.run(['javac','--release','17','-d',str(classes),str(repo/'scripts/qa/DesktopInput.java')],check=True)
libraries=[];inputs=[{'path':'versions/'+a.version+'/client.jar','sha256':sha(client)},{'path':'versions/'+a.version+'/version.json','sha256':sha(version/'version.json')}]
for lib in meta['libraries']:
 allowed=not lib.get('rules')
 for rule in lib.get('rules',[]):
  os=rule.get('os',{})
  if os.get('name','linux')!='linux' or not re.fullmatch(os.get('arch','amd64'),'amd64') or rule.get('features'):continue
  allowed=rule['action']=='allow'
 if allowed and 'artifact' in lib.get('downloads',{}):
  ref=lib['downloads']['artifact'];path=cache/'libraries'/ref['path']
  if hashlib.sha1(path.read_bytes()).hexdigest()!=ref['sha1']:raise ValueError('library hash不一致: '+ref['path'])
  libraries.append('/mcicon/cache/libraries/'+ref['path']);inputs.append({'path':str(path.relative_to(cache)),'sha256':sha(path)})
docker=['docker','--context',a.docker_context]
image=subprocess.run(docker+['image','inspect',a.docker_image,'--format','{{.Id}}'],capture_output=True,text=True,check=True).stdout.strip()
active=subprocess.run(docker+['ps','--format','{{.Names}}'],capture_output=True,text=True,check=True).stdout.splitlines()
if sum(n.startswith(('mcicon-worker-','mcicon-world-qa-'))for n in active)>=2:raise RuntimeError('native clientの同時実行は2つまでです')
container='mcicon-world-qa-uninstrumented-'+str(uuid.uuid4())
command=['/opt/java/java21/bin/java','-Xmx2G','-Duser.timezone=UTC','-Djava.library.path=/mcicon/cache/versions/'+a.version+'/natives-linux','-cp',':'.join(['/mcicon/cache/versions/'+a.version+'/client.jar']+libraries),meta['mainClass'],'--username','MCIconGenerator','--version',a.version,'--gameDir','/mcicon/qa/game','--assetsDir','/mcicon/cache/assets','--assetIndex',meta['assetIndex']['id'],'--accessToken','0','--uuid','00000000000000000000000000000000','--userType','legacy','--width','1280','--height','960','--disableMultiplayer']
(out/'start.sh').write_text('#!/bin/sh\nexport DISPLAY=:99\nXvfb :99 -screen 0 1280x960x24 -ac -nolisten tcp > /mcicon/qa/xvfb.log 2>&1 &\nsleep 2\nexec '+shlex.join(command)+' > /mcicon/qa/client.log 2>&1\n')
report={'scope':'clientは元JARと公式libraryだけで起動する。別JVMのAWT Robotで通常UI・vanilla command・F2を操作する。saveは入力としてコピーし、時刻・描画・animationを変更しない。','version':a.version,'imageId':image,'sourceSha256':sha(client),'inputs':inputs,'worldInputs':world_inputs,'worldSource':'指定された保存済みワールド。生成時の無計装性は保証せず、今回の描画clientだけを無計装とする。','windowPixels':[1280,960],'guiScale':4,'slot':9,'slotGui':[80,121],'count':1,'componentOverrides':{},'qaReferenceReportSha256':sha(reference/'report.json'),'qaReferenceImages':{id:sha(path)for id,path in qa_images.items()},'clientCommand':command,'controllerClassSha256':sha(classes/'DesktopInput.class'),'harnessSourcesSha256':{str(f.relative_to(repo)):sha(f) for f in [Path(__file__).resolve(),repo/'scripts/qa/DesktopInput.java',repo/'scripts/audit-export.py']},'operations':[],'captures':[],'completed':False}
save=lambda:(out/'capture.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
save()
def run(*args):return subprocess.run(docker+list(args),capture_output=True,text=True,check=True,timeout=120)
def act(*args):
 run('exec','-e','DISPLAY=:99',container,'/opt/java/java21/bin/java','-cp','/mcicon/control','DesktopInput',*map(str,args));report['operations'].append({'action':list(args),'elapsedSeconds':round(time.monotonic()-start,3)});save()
def wait(check,seconds,message):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  if check():return
  time.sleep(.25)
 raise RuntimeError(message)
def chat(s):
 log=out/'client.log';offset=len(log.read_text());act('key',84);act('type',s);act('key',10)
 expected='Replaced a slot on MCIconGenerator with ['+s.rsplit(':',1)[1].replace('_',' ').title()+']' if s.startswith('/item replace ') else 'Removed '
 wait(lambda:expected in log.read_text()[offset:] or s=='/clear' and 'No items were found' in log.read_text()[offset:],10,'vanilla commandの成功を確認できません: '+s)
 report.setdefault('commandConfirmations',[]).append({'command':s,'logCharacterOffset':offset,'expected':expected,'observed':log.read_text()[offset:]});save();time.sleep(1)
start=time.monotonic()
try:
 run('run','-d','--name',container,'--platform','linux/amd64','--network','none','--memory','3g','--cpus','4','--entrypoint','/bin/sh','-e','JAVA_TOOL_OPTIONS=','-e','JDK_JAVA_OPTIONS=','-e','_JAVA_OPTIONS=','-v',str(cache)+':/mcicon/cache:ro','-v',str(out)+':/mcicon/qa','-v',str(classes)+':/mcicon/control:ro',image,'/mcicon/qa/start.sh')
 wait(lambda:(out/'client.log').exists() and 'minecraft:textures/atlas/gui.png-atlas' in (out/'client.log').read_text(),90,'titleのロードが完了しません');time.sleep(2)
 act('screen','/mcicon/qa/title.png');act('click',640,470);time.sleep(2);act('screen','/mcicon/qa/world-menu.png');act('click',480,260);act('click',320,790)
 wait(lambda:'joined the game' in (out/'client.log').read_text(),90,'worldのロードが完了しません');time.sleep(8)
 proof=run('exec',container,'/bin/sh','-c','cat /proc/1/cmdline; printf "\\n"; cat /proc/1/environ; printf "\\n"; cat /proc/1/maps').stdout
 (out/'process-proof.txt').write_text(proof)
 report['clientProcessProofSha256']=sha(out/'process-proof.txt')
 report['instrumentationAbsent']=all(x not in proof for x in ['-javaagent','-agentlib','-agentpath','libinstrument.so','libattach.so'])
 if not report['instrumentationAbsent']:raise RuntimeError('clientの無計装性を確認できません')
 chat('/clear');act('key',69);act('move',1100,900);time.sleep(2)
 for id in ['empty']+ids:
  if id!='empty':act('key',69);chat('/item replace entity @s inventory.0 with minecraft:'+id);act('key',69);act('move',1100,900);time.sleep(2)
  for sample in range(a.samples):
   before=set((game/'screenshots').glob('*.png')) if (game/'screenshots').exists() else set();act('key',113)
   wait(lambda:len(set((game/'screenshots').glob('*.png'))-before)==1,20,'F2 screenshotが生成されません')
   path=(set((game/'screenshots').glob('*.png'))-before).pop()
   wait(lambda:path.stat().st_size>1000,5,'F2 screenshotの保存が完了しません');time.sleep(.3)
   target=out/(id+'-'+str(sample)+'-native.png');shutil.copyfile(path,target)
   slot=crop(target)
   qa_match=slot==qa_pixels[id] if id in qa_pixels else None
   if qa_match is False:raise RuntimeError('受入済みWorld QAのslot画像と一致しません: '+id)
   report['captures'].append({'qaSlotRgbaMatches':qa_match,'slotRgbaSha256':hashlib.sha256(slot).hexdigest(),'id':'minecraft:'+id if id!='empty'else'empty','sample':sample,'screen':target.name,'sha256':sha(target),'count':0 if id=='empty' else 1,'slotGui':[80,121],'elapsedSeconds':round(time.monotonic()-start,3)})
   save();print(id,sample,flush=True);time.sleep(2)
 act('key',69);act('key',27);act('click',640,696)
 wait(lambda:'Saving chunks for level' in (out/'client.log').read_text(),30,'worldの保存が完了しません');time.sleep(3)
 act('click',840,810)
 status=run('wait',container).stdout.strip();report['exitCode']=int(status)
 report['worldOutputLevelDatSha256']=sha(game/'saves'/'MCIcon QA'/'level.dat')
 report['officialInputsModified']=any(sha(cache/e['path'])!=e['sha256']for e in inputs)
 report['completed']=report['exitCode']==0 and not report['officialInputsModified'];save()
 if not report['completed']:raise SystemExit(1)
except BaseException as error:
 report['failure']={'type':type(error).__name__,'message':str(error)};save()
 subprocess.run(docker+['stop','--time','5',container],capture_output=True)
 raise
