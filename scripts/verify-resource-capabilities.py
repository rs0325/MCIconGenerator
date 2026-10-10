#!/usr/bin/env python3
"""Check resource-observer dependencies against actual cached client bytecode."""
import argparse,hashlib,json,shutil,subprocess,tempfile
from pathlib import Path
repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--cache',type=Path,default=repo/'.local/cache');p.add_argument('--out',type=Path,required=True)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
versions=[v['id'] for v in json.loads((repo/'adapters/releases.json').read_text())['versions']]
legacy={'1.13','1.13.1','1.13.2','1.14','1.14.1','1.14.2','1.14.3'};results=[]
with tempfile.TemporaryDirectory(prefix='.agent-snapshot-',dir=out) as temporary:
 agent=Path(temporary)/'worker.jar';shutil.copyfile(repo/'cli/build/install/mcicon/worker/worker-agent.jar',agent)
 classes=out/'classes';classes.mkdir();subprocess.run(['javac','--release','21','-cp',str(agent),'-d',str(classes),str(repo/'scripts/qa/ResourceCapabilityProbe.java')],check=True)
 for version in versions:
  folder=a.cache.resolve()/'versions'/version;mapping=folder/'mappings.txt'
  result=subprocess.run(['java','-cp',str(classes)+':'+str(agent),'io.github.rs0325.mcicon.qa.ResourceCapabilityProbe',version,str(folder/'client.jar'),str(mapping) if mapping.is_file() else '-',str(version in legacy).lower()],text=True,capture_output=True)
  (out/(version+'.log')).write_text(result.stderr)
  try:report=json.loads(result.stdout)
  except Exception:report={'version':version,'failures':['Probe did not produce JSON']}
  report['exitCode']=result.returncode;results.append(report)
  print(version,'PASS' if not result.returncode and not report['failures'] else 'FAIL',flush=True)
 (out/'report.json').write_text(json.dumps({'agentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),'gameWorkersStarted':0,'versions':results},indent=2)+'\n')
raise SystemExit(any(r['exitCode'] or r['failures'] for r in results))
