#!/usr/bin/env python3
"""指定版の通常ワールド・InventoryScreenを描画workerとは独立に撮影する。"""
import argparse,hashlib,importlib.util,json,re,subprocess,uuid,zipfile
from pathlib import Path

repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--out',type=Path,required=True)
p.add_argument('--version',choices=['1.13','1.13.1','1.13.2','1.14','1.14.1','1.14.2','1.14.3','1.14.4','1.15','1.15.1','1.15.2','1.16','1.16.1','1.16.2','1.16.3','1.16.4','1.16.5','1.17','1.17.1','1.18','1.18.1','1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5','1.20.6','1.21','1.21.1','1.21.2','1.21.3','1.21.4'],default='1.21.4')
p.add_argument('--cache',type=Path,default=repo/'.local/cache')
p.add_argument('--compare',type=Path,required=True,help='指定版の64px出力を含むディレクトリ')
p.add_argument('--ids',nargs='+',default=['stone','diamond','chest','ender_chest','white_bed','white_banner','shield','player_head','dragon_head','white_shulker_box','glass','white_stained_glass','ice','grass_block','oak_leaves','potion','creeper_spawn_egg','leather_chestplate'])
p.add_argument('--docker-context',default='colima-mcicon')
p.add_argument('--docker-image',default='mcicon-render:dev')
p.add_argument('--frame-zero',action='store_true',help='元PNGの初期animation frameだけを選ぶQAパックで固定フレームを照合する')
p.add_argument('--fixed-glint',action='store_true',help='通常GUIのglint時刻だけを1000msへ指定して固定条件を照合する')
a=p.parse_args()
legacy=a.version in ['1.13','1.13.1','1.13.2','1.14','1.14.1','1.14.2','1.14.3']
fixed=a.version in ['1.14.4','1.15','1.15.1','1.15.2','1.16','1.16.1','1.16.2','1.16.3','1.16.4','1.16.5']
classic=a.version in ['1.17','1.17.1','1.18','1.18.1','1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4']
modern=a.version in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5','1.21','1.21.1','1.21.2','1.21.3']
if (legacy or fixed or classic or modern) and (a.frame_zero or a.fixed_glint):p.error('legacy/gui-fixed/gui-classic/gui-1.21.4系の撮影は通常の静的アイテムに限定します')
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cache=a.cache.resolve();version=cache/'versions'/a.version;meta=json.loads((version/'version.json').read_text())
ids=['minecraft:'+i if ':' not in i else i for i in a.ids]
if len(ids)!=len(set(ids)):p.error('IDは重複できません')
manifest=a.compare.resolve()/'java'/a.version/'icons/manifest.json';baseline=json.loads(manifest.read_text())
entries={e['id']:e for e in baseline['entries']}
if a.version=='1.21.4' and baseline['context'].get('lightmapFullBrightRgb')!=[252,252,252]:p.error('実インベントリ照明を反映した1.21.4のPNGを再生成してください')
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
 excluded=['RenderAgent','CanonicalTransformer','CanonicalContext','ResourceTransformer','ModernRenderer','ClassicRenderer','FixedRenderer','LegacyRenderer','PngOutput','Bridge']
 assert not any('io/github/rs0325/mcicon/worker/'+name+'.class' in archive.namelist() for name in excluded)
game=out/'game';game.mkdir()
(game/'options.txt').write_text('soundCategory_master:0.0\nguiScale:4\nmaxFps:30\nfullscreen:false\nonboardAccessibility:false\nrenderDistance:2\nsimulationDistance:5\nclouds:false\n')
config={'version':a.version,'mappings':'/mcicon/cache/versions/'+a.version+'/mappings.txt','output':'/mcicon/qa','ids':ids}
if a.fixed_glint:config['fixedGlintMillis']=1000
animation_inputs=[]
if a.frame_zero:
 packs=game/'resourcepacks';packs.mkdir();pack=packs/'mcicon-frame-zero.zip'
 with zipfile.ZipFile(client) as source,zipfile.ZipFile(pack,'w',compression=zipfile.ZIP_DEFLATED) as target:
  pack_format=json.loads(source.read('version.json'))['pack_version']['resource']
  target.writestr('pack.mcmeta',json.dumps({'pack':{'pack_format':pack_format,'description':'MCIcon QA: 元テクスチャの初期フレーム'}}))
  for name in sorted(source.namelist()):
   if not name.startswith(('assets/minecraft/textures/item/','assets/minecraft/textures/block/')) or not name.endswith('.png.mcmeta'):continue
   metadata=source.read(name);data=json.loads(metadata)
   if 'animation' not in data:continue
   frames=data['animation'].get('frames',[0]);first=frames[0] if frames else 0
   first=first['index'] if isinstance(first,dict) else first
   data['animation']['frames']=[first];data['animation']['interpolate']=False
   texture=name.removesuffix('.mcmeta');pixels=source.read(texture)
   target.writestr(texture,pixels);target.writestr(name,json.dumps(data))
   animation_inputs.append({'path':texture,'pngSha256':hashlib.sha256(pixels).hexdigest(),'metadataSha256':hashlib.sha256(metadata).hexdigest(),'initialFrame':first})
 config['frameZeroResources']=['minecraft:'+e['path'].removeprefix('assets/minecraft/') for e in animation_inputs]
 with (game/'options.txt').open('a') as options:options.write('resourcePacks:["vanilla","file/mcicon-frame-zero.zip"]\nincompatibleResourcePacks:[]\n')
(out/'agent.json').write_text(json.dumps(config))
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
java='/opt/java/java8/bin/java' if legacy or fixed else '/opt/java/java17/bin/java' if classic or meta['javaVersion']['majorVersion']==17 else '/opt/java/java21/bin/java'
command=['docker','--context',a.docker_context,'run','--rm','--name',container,'--platform','linux/amd64','--network','none','--memory','3g','--cpus','4','-v',str(cache)+':/mcicon/cache:ro','-v',str(out)+':/mcicon/qa',image,java,'-Xmx1G' if classic or modern else '-Xmx2G','-Djava.awt.headless=true','-Duser.timezone=UTC','-Djava.library.path=/mcicon/cache/versions/'+a.version+'/natives-linux','-javaagent:/mcicon/qa/world-reference.jar=/mcicon/qa/agent.json','-cp',':'.join(['/mcicon/cache/versions/'+a.version+'/client.jar']+libraries),meta['mainClass'],'--username','MCIconGenerator','--version',a.version,'--gameDir','/mcicon/qa/game','--assetsDir','/mcicon/cache/assets','--assetIndex',meta['assetIndex']['id'],'--accessToken','0','--uuid','00000000000000000000000000000000','--userType','legacy','--width','1280','--height','960','--disableMultiplayer']
report={'scope':'通常のローカルflatワールドのInventoryScreen。QA callbackはMinecraft.runTick末尾のみで、ゲーム状態・画面操作とnative Screenshotを使用する。描画メソッド、production初期化、canonical hook、透過復元を共有しない。完全な無計装clientではない。','version':a.version,'sourceSha256':source_hash,'baselineAgentSha256':baseline['settings']['agentSha256'],'qaAgentSha256':sha(agent),'imageId':image,'harnessSourcesSha256':{str(f.relative_to(repo)):sha(f) for f in [Path(__file__).resolve(),repo/'scripts/qa/WorldInventoryAgent.java']},'windowPixels':[1280,960],'guiScale':4,'worldPreset':'flat','seed':'0','slot':9,'excludedProductionClasses':excluded,'maxAllowedChannelError':2,'baselineFullBrightRgb':baseline['context'].get('lightmapFullBrightRgb',[255,255,255]),'baselineLightmapSource':'manifest' if 'lightmapFullBrightRgb' in baseline['context'] else 'client-constructor-default-white','entries':[],'passed':False}
report['resourceLimits']={'heapMiB':1024 if classic or modern else 2048,'containerMiB':3072}
if legacy or fixed or classic or modern:
 report['scope']='通常のローカルflatワールドのインベントリ。通常フレーム末尾で画面操作・native Screenshot・CPU/GPU照明値を読み取る。対象スロットのGUIモデル描画直前にlightmap texture unitの有効状態だけを観測し、元のactive textureを復帰する。production描画・固定化・透過復元を共有しない。完全な無計装clientではない。'
 report['baselineFullBrightRgb']=baseline['context'].get('lightmapFullBrightRgb',[255,255,255] if classic or modern else None);report['baselineLightmapSource']='manifest' if 'lightmapFullBrightRgb' in baseline['context'] else 'client-constructor-default-white' if classic or modern else '対象スロットのモデル描画入口でlightmap texture unit無効を実測する。GUI内部の全描画状態を保証する値ではない。'
 if classic or modern:report['scope']='通常のローカルflatワールドのインベントリ。通常フレーム末尾で画面操作・native Screenshotを行う。CPU画像がある版はCPU/GPU texel、GPU target版はScreenshot/GPU texelを読み取る。対象スロットのモデル描画入口ではRenderSystemのshader texture番号2を読み、固定機能のGL_TEXTURE_2D有効状態は使用しない。production描画・固定化・透過復元を共有しない。完全な無計装clientではない。'
save=lambda:(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
if a.frame_zero:
 report['frameZeroPack']={'sha256':sha(pack),'resources':animation_inputs,'productionHooksUsed':False}
if a.fixed_glint:
 report['scope']='通常ワールドのInventoryScreenでglint用のUtil.getMillis呼び出し1箇所だけを1000msへ指定するQA。productionの固定化・bootstrap・PNG復元は共有しない。glint時刻を変更した管理下の参照画像である。'
 report['fixedGlintMillis']=1000
if a.frame_zero:report['scope']+=' animationの元PNGを保持し、metadataのframesを最初のframe1件、interpolateをfalseにするQAパックを使用する。'
save()
try:
 with (out/'client.log').open('w') as log:run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=300)
 report['exitCode']=run.returncode
except (subprocess.TimeoutExpired,KeyboardInterrupt) as error:
 subprocess.run(['docker','--context',a.docker_context,'stop','--time','5',container],capture_output=True)
 report['timedOut']=isinstance(error,subprocess.TimeoutExpired);report['interrupted']=isinstance(error,KeyboardInterrupt);save();raise
save()
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
spec=importlib.util.spec_from_file_location('inventory_comparison',repo/'scripts/inventory-comparison.py');comparison=importlib.util.module_from_spec(spec);spec.loader.exec_module(comparison)
report['comparisonSourceSha256']=sha(repo/'scripts/inventory-comparison.py')
report['edgePolicy']={'approvedReference':comparison.APPROVED,'scope':'ユーザーが画像確認したoak_fenceの同一PNG・参照RGBA・背景RGBA・2座標だけを許容。他画像へ自動的に一般化しない。'}
if run.returncode:
 if (out/'capture.json').exists():report['capture']=json.loads((out/'capture.json').read_text())
 save();raise SystemExit(1)
capture=json.loads((out/'capture.json').read_text());report['capture']=capture
if not capture.get('completed',False):save();raise SystemExit(1)
if a.frame_zero:
 sources=capture.get('frameZeroResourceSources',{})
 report['frameZeroPack']['sourcesConfirmed']=len(sources)==len(animation_inputs) and all(sources.get(id,'').endswith('mcicon-frame-zero.zip') for id in config['frameZeroResources'])
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
 native=slot(c)
 light=[(c['fullBrightArgb']>>shift)&255 for shift in (16,8,0)]
 metrics=comparison.compare(rgba,native,background,item_id=c['id'],export_sha=e['sha256'])
 opaque=all(native[i]==255 and background[i]==255 for i in range(3,len(native),4))
 entry={'id':c['id'],'exportPngSha256':e['sha256'],'screenSha256':sha(out/c['screen']),**metrics,'count':c['count'],'fullBrightRgb':light,'opaqueReference':opaque,'rawPassed':metrics['pixelsOverTolerance']==0 and c['count']==1 and opaque,'lightmapMatches':light==report['baselineFullBrightRgb']}
 if legacy or fixed or classic or modern:
  entry['fullBrightGpuRgba']=c['fullBrightGpuRgba']
  if 'fullBrightCpuRgba' in c:
   entry['fullBrightCpuRgba']=c['fullBrightCpuRgba'];entry['lightmapGpuMatchesCpu']=entry['fullBrightCpuRgba']==entry['fullBrightGpuRgba']
  else:
   entry['fullBrightScreenshotRgba']=c['fullBrightScreenshotRgba'];entry['lightmapGpuMatchesScreenshot']=entry['fullBrightScreenshotRgba']==entry['fullBrightGpuRgba']
  if classic or modern:entry['guiLightmapShaderTexture']=c['guiLightmapShaderTexture']
  else:entry['guiLightmapTextureEnabled']=c['guiLightmapTextureEnabled']
  entry['guiLightmapTextureUnit']=c['guiLightmapTextureUnit'];entry['lightmapMatches']=None
  entry['lightingCompatible']=(entry['lightmapGpuMatchesCpu'] if 'lightmapGpuMatchesCpu' in entry else entry['lightmapGpuMatchesScreenshot']) and (not entry['guiLightmapTextureEnabled'] if legacy or a.version=='1.14.4' else light==report['baselineFullBrightRgb'])
 lighting=entry['lightingCompatible'] if legacy or fixed or classic or modern else entry['lightmapMatches']
 report['entries'].append(entry);print(c['id'],'PASS' if entry['comparisonPassed'] and entry['count']==1 and lighting and opaque else 'FAIL',metrics['maxChannelError'],flush=True)
report['officialInputsModified']=sha(client)!=source_hash
complete=run.returncode==0 and capture.get('completed',False) and len(report['entries'])==len(ids) and not report['officialInputsModified']
report['passed']=complete and all(e['comparisonPassed'] and e['count']==1 and e['opaqueReference'] and (e['lightingCompatible'] if legacy or fixed or classic or modern else e['lightmapMatches']) for e in report['entries']) and (not a.frame_zero or report['frameZeroPack']['sourcesConfirmed']) and (not a.fixed_glint or capture.get('glintClockCallSites')==1 and capture.get('fixedGlintMillis')==1000)
save();raise SystemExit(not report['passed'])
