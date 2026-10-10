#!/usr/bin/env python3
"""Prepare one cached legacy version concurrently in independent JVMs."""
import argparse,concurrent.futures,hashlib,json,shutil,subprocess,tempfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--cache',type=Path,default=repo/'.local/cache');p.add_argument('--out',type=Path,required=True);p.add_argument('--version',default='1.13');p.add_argument('--rounds',type=int,choices=range(1,11),default=4)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
with tempfile.TemporaryDirectory(prefix='.cli-snapshot-',dir=out) as temporary:
 distribution=Path(temporary)/'mcicon';shutil.copytree(repo/'cli/build/install/mcicon',distribution)
 jars=list((distribution/'lib').glob('*.jar'));classpath=':'.join(map(str,jars));core=next(p for p in jars if p.name.startswith('core-'))
 classes=out/'classes';classes.mkdir()
 subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/RuntimePrepareProbe.java')],check=True)
 command=['java','-cp',str(classes)+':'+classpath,'io.github.rs0325.mcicon.qa.RuntimePrepareProbe',str(a.cache.resolve()),a.version]
 def run(name):
  with (out/(name+'.json')).open('w') as stdout,(out/(name+'.log')).open('w') as stderr:
   result=subprocess.run(command,stdout=stdout,stderr=stderr)
  return result.returncode
 assert run('baseline')==0,'Baseline preparation failed; see baseline.log'
 baseline=json.loads((out/'baseline.json').read_text());results=[]
 for round in range(a.rounds):
  names=[f'round-{round+1}-{i+1}' for i in range(2)]
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:codes=list(pool.map(run,names))
  for name,code in zip(names,codes):
   result=json.loads((out/(name+'.json')).read_text()) if code==0 else None
   results.append({'run':name,'exitCode':code,'derivedBytesMatchBaseline':result==baseline})
  print('round',round+1,'PASS' if all(r['exitCode']==0 and r['derivedBytesMatchBaseline'] for r in results[-2:]) else 'FAIL',flush=True)
 report={'version':a.version,'independentJvmProcesses':len(results),'concurrency':2,'offline':True,'gameWorkersStarted':0,'cliCoreSha256':hashlib.sha256(core.read_bytes()).hexdigest(),'baseline':baseline,'results':results,'previousFailureRootCauseEstablished':False}
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
raise SystemExit(any(r['exitCode']!=0 or not r['derivedBytesMatchBaseline'] for r in results))
