#!/usr/bin/env python3
"""Check five curated flat sprites/layers without loading game classes or drawing on a GPU."""
import argparse,hashlib,json,subprocess
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--compare',type=Path,required=True);p.add_argument('--cache',type=Path,default=repo/'.local/cache');a=p.parse_args()
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir()
agent=repo/'cli/build/install/mcicon/worker/worker-agent.jar';source=repo/'scripts/qa/FlatTextureProbe.java'
subprocess.run(['javac','--release','8','-cp',str(agent),'-d',str(classes),str(source)],check=True)
report={'scope':'Curated 16px generated-model layers, transparent/opaque source alpha, white or JSON constant tints. Independent of game classes, GPU, canonical hooks and export composition. Not a general Minecraft model renderer.','gameWorkersStarted':0,'qaClasspathAgentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),'maxAllowedRgbError':1,'maxAllowedAlphaError':0,'harnessSourcesSha256':{str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(),source]},'versions':[]}
for version,ids in [('1.13',['diamond']),('1.21.4',['diamond','creeper_spawn_egg']),('26.3',['diamond','creeper_spawn_egg'])]:
 run=subprocess.run(['java','-Djava.awt.headless=true','-cp',str(classes)+':'+str(agent),'io.github.rs0325.mcicon.qa.FlatTextureProbe',version,str(a.cache.resolve()/'versions'/version/'client.jar'),str(a.compare.resolve()/'java'/version/'icons'),*ids],capture_output=True,text=True)
 (out/(version+'.log')).write_text(run.stderr)
 result=json.loads(run.stdout);result['exitCode']=run.returncode;report['versions'].append(result);(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 print(version,'PASS' if result['passed'] and not run.returncode else 'FAIL',[(r['id'],r['maxRgbError'],r['maxAlphaError']) for r in result['results']],flush=True)
raise SystemExit(any(not v['passed'] or v['exitCode'] for v in report['versions']))
