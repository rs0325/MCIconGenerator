#!/usr/bin/env python3
"""ゲーム・GPUを使わず、平面アイテムとentityなしの針モデルを元リソースと比較する。"""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
releases=[v['id'] for v in json.loads((repo/'adapters/releases.json').read_text())['versions']]
versions=releases
definition_versions=set(releases[:releases.index('1.21.4')+1])
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--compare',type=Path);p.add_argument('--cache',type=Path,default=repo/'.local/cache');p.add_argument('--atlas-log',type=Path,help='1.21.4の比較出力を生成したworkerのatlas寸法ログ');p.add_argument('--versions',nargs='+',choices=versions,default=['1.13','1.21.4','26.3']);p.add_argument('--needle-proofs',type=Path,help='元bytecode・World照明・samplingを検査した版別JSONのディレクトリ');p.add_argument('--prepare-only',action='store_true',help='針のRGBA期待値をPNG生成前に保存する');p.add_argument('--prepared',type=Path,help='PNG生成前の針のRGBA期待値');a=p.parse_args()
if not a.prepare_only and not a.compare:p.error('--compareが必要です')
if a.prepare_only and not a.needle_proofs:p.error('--prepare-onlyには--needle-proofsが必要です')
if a.needle_proofs and not a.prepare_only and not a.prepared:p.error('針比較には生成前の--preparedが必要です')
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir()
agent=repo/'cli/build/install/mcicon/worker/worker-agent.jar';source=repo/'scripts/qa/FlatTextureProbe.java';needle_source=repo/'scripts/qa/NeedleTextureProbe.java'
dependencies=out/'qa-dependencies.jar'
with zipfile.ZipFile(agent) as original,zipfile.ZipFile(dependencies,'w') as isolated:
 for entry in original.namelist():
  if entry.startswith('com/google/gson/') and not entry.endswith('/'):isolated.writestr(entry,original.read(entry))
subprocess.run(['javac','--release','8','-cp',str(dependencies),'-d',str(classes),str(source),str(needle_source)],check=True)
if a.needle_proofs:
 results=[]
 for version in a.versions:
  proof=a.needle_proofs.resolve()/(version+'.json')
  command=['java','-Djava.awt.headless=true','-cp',str(classes)+':'+str(dependencies),'io.github.rs0325.mcicon.qa.FlatTextureProbe','--needles',str(a.cache.resolve()/'versions'/version/'client.jar'),str(proof)]
  command+=['--prepare'] if a.prepare_only else [str(a.compare.resolve()/'java'/version/'icons'),str(a.prepared.resolve()/(version+'.json'))]
  run=subprocess.run(command,capture_output=True,text=True)
  (out/(version+'.log')).write_text(run.stderr)
  try:result=json.loads(run.stdout)
  except ValueError:result={'version':version,'passed':False,'failures':['針oracleがJSONを返しませんでした']}
  result['exitCode']=run.returncode;results.append(result);(out/(version+'.json')).write_text(json.dumps(result,indent=2)+'\n')
  print(version,'PASS' if result['passed'] and not run.returncode else 'FAIL',flush=True)
 report={'scope':'固定51版のworld/playerなし既定針、16pxだけ。PNG生成前の期待値・採取根拠と元資源を照合し、RGB1/255・alpha0で比較する。','prepareOnly':a.prepare_only,'gameWorkersStarted':0,'productionClassesOnClasspath':False,'qaDependenciesSha256':hashlib.sha256(dependencies.read_bytes()).hexdigest(),'maxAllowedRgbError':1,'maxAllowedAlphaError':0,'harnessSourcesSha256':{str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(),source,needle_source]},'versions':results,'passed':all(v['passed'] and not v['exitCode'] for v in results)}
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');raise SystemExit(not report['passed'])
report={'scope':'旧モデル形式は無着色diamond、item definitions形式は元の16px generated texture・定数tint、および1.21.4のentityなし・向き0の針モデルを比較する。ゲームクラス・GPU・固定化hook・PNG合成は使わない。一般のモデルrendererではない。--atlas-log指定時は1.21.4の実測atlas寸法と公式FaceBakery/TextureAtlasSpriteのUV縮小を計算する。','gameWorkersStarted':0,'qaClasspathAgentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),'maxAllowedRgbError':1,'maxAllowedAlphaError':0,'productionClassesOnClasspath':False,'qaDependenciesSha256':hashlib.sha256(dependencies.read_bytes()).hexdigest(),'harnessSourcesSha256':{str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(),source,needle_source]},'versions':[]}
for version in a.versions:
 ids=['diamond','creeper_spawn_egg','clock','compass','recovery_compass'] if version=='1.21.4' else ['diamond','creeper_spawn_egg'] if version in definition_versions else ['diamond']
 run=subprocess.run(['java','-Djava.awt.headless=true','-cp',str(classes)+':'+str(dependencies),'io.github.rs0325.mcicon.qa.FlatTextureProbe',version,str(a.cache.resolve()/'versions'/version/'client.jar'),str(a.compare.resolve()/'java'/version/'icons'),*(['--atlas-log',str(a.atlas_log.resolve())] if version=='1.21.4' and a.atlas_log else []),*ids],capture_output=True,text=True)
 (out/(version+'.log')).write_text(run.stderr)
 result=json.loads(run.stdout);result['exitCode']=run.returncode;report['versions'].append(result);(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 print(version,'PASS' if result['passed'] and not run.returncode else 'FAIL',[(r['id'],r['maxRgbError'],r['maxAlphaError']) for r in result['results']],flush=True)
raise SystemExit(any(not v['passed'] or v['exitCode'] for v in report['versions']))
