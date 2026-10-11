#!/usr/bin/env python3
"""Controlled 1.21.4 cold-cache and warm-cache native exports, one worker at a time.

Cold includes official HTTPS downloads into an empty cache. Warm uses those same
inputs offline and a fresh output directory, so it cannot reuse existing icons.
Requires the local Docker image, JDK21 and a built CLI distribution.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--warm-runs',type=int,default=2)
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
if not 1<=args.warm_runs<=5:parser.error('Use 1–5 warm runs')
out=args.out.resolve()
if out.exists():parser.error('--out must be new')
running=subprocess.run(['docker','--context',args.docker_context,'ps','--filter','name=mcicon-worker-','--format','{{.Names}}'],capture_output=True,text=True,check=True)
if running.stdout.strip():parser.error('Stop/wait for other MCIcon workers before measuring')
out.mkdir(parents=True);cache=out/'cache';cache.mkdir();classes=out/'classes';classes.mkdir()
distribution=out/'cli';shutil.copytree(repo/'cli/build/install/mcicon',distribution)
classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/NativeBenchmarkProbe.java')],check=True)
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py')
audit_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit_module)
results=[]
for run in ['cold',*(f'warm-{i+1}' for i in range(args.warm_runs))]:
    output=out/run
    with (out/(run+'.log')).open('w') as log:
        result=subprocess.run(['java','-cp',str(classes)+os.pathsep+classpath,'NativeBenchmarkProbe',str(cache),str(output),str(run!='cold').lower(),
            str(distribution/'worker/worker-agent.jar'),args.docker_context,args.docker_image],stdout=subprocess.PIPE,stderr=log,text=True,timeout=600)
    assert result.returncode==0,(out/(run+'.log')).read_text()
    report=json.loads(result.stdout);report['run']=run
    manifest=output/'java/1.21.4/icons/manifest.json'
    audit=audit_module.audit(manifest,compare=None if run=='cold' else out/'cold/java/1.21.4/icons/manifest.json',require_all=True)
    assert not audit['failures'],audit
    report['audit']=audit
    metadata=json.loads(manifest.read_text())
    report.update(agentSha256=metadata['settings']['agentSha256'],sourceSha256=metadata['sourceSha256'],imageId=metadata['environment']['imageId'])
    files=[path for path in cache.rglob('*') if path.is_file() and not path.relative_to(cache).parts[0].startswith('worker-')]
    report['preparedCacheBytes']=sum(path.stat().st_size for path in files);report['preparedCacheFiles']=len(files)
    results.append(report);(out/'report.json').write_text(json.dumps({'runs':results,'coldIncludesNetwork':True,'warmUsesFreshOutput':True,'memorySample':'kernel cumulative high-water marks after the final icon, before shutdown','performanceGuarantee':False},indent=2)+'\n')
    print(run,round(report['elapsedSeconds'],2),'seconds',report['workerVmHwmBytesBeforeShutdown'],'RSS bytes',flush=True)
