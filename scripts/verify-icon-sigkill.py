#!/usr/bin/env python3
"""Kill the core JVM after three native icons, then audit a fresh CLI resume.

Requires a prepared 1.13 cache, baseline full export and local Docker. Uses one
worker; run after any two-worker matrix. Only the actor's own container is checked.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import subprocess
import time

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--cache',type=Path,default=repo/'.local/cache')
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--compare',type=Path,required=True)
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
out=args.out.resolve();cache=args.cache.resolve()
if out.exists():parser.error('--out must be new')
out.mkdir(parents=True);classes=out/'classes';classes.mkdir()
distribution=out/'cli';shutil.copytree(repo/'cli/build/install/mcicon',distribution)
classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/IconCrashProbe.java')],check=True)
agent=repo/'worker/build/libs/worker-0.1.0-SNAPSHOT-agent.jar'
log=(out/'actor.log').open('w')
process=subprocess.Popen(['java','-cp',str(classes)+os.pathsep+classpath,'IconCrashProbe',str(cache),str(out),str(agent),args.docker_context,args.docker_image],stdout=subprocess.PIPE,stderr=log,text=True)
container=None
try:
    selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
    assert selector.select(180),'Native actor did not become ready'
    line=process.stdout.readline().strip()
    assert line.startswith('READY_FOR_KILL mcicon-worker-'),line
    container=line.split()[1]
    os.kill(process.pid,signal.SIGKILL);assert process.wait(10)==-signal.SIGKILL
finally:
    if process.poll() is None:process.kill();process.wait()
    process.stdout.close();selector.close();log.close()
docker=['docker','--context',args.docker_context]
started=time.monotonic();deadline=started+30
while subprocess.run([*docker,'container','inspect',container],capture_output=True).returncode==0:
    if time.monotonic()>deadline:
        subprocess.run([*docker,'rm','-f',container],capture_output=True)
        raise AssertionError('Owned worker did not exit on stdin EOF after core SIGKILL; manually removed it')
    time.sleep(0.25)
worker_exit_seconds=round(time.monotonic()-started,2)
manifest=out/'java/1.13/icons/manifest.json';interrupted=json.loads(manifest.read_text())
assert not interrupted['complete'] and interrupted['counts']['failed']>0
command=[str(distribution/'bin/mcicon'),'render','--version','1.13','--all','--size','64','--cache',str(cache),'--out',str(out),'--offline','--allow-experimental','--json',
         '--docker-context',args.docker_context,'--docker-image',args.docker_image]
with (out/'resume.json').open('w') as output,(out/'resume.log').open('w') as log:
    result=subprocess.run(command,stdout=output,stderr=log,timeout=180)
assert result.returncode==0,(out/'resume.log').read_text()
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py')
audit_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit_module)
audit=audit_module.audit(manifest,compare=args.compare.resolve()/'java/1.13/icons/manifest.json',require_all=True)
assert not audit['failures'] and audit['comparisonVerified'] and not list((out/'java').rglob('*.part')),audit
report={'coreSignal':'SIGKILL','checkpointAfterRendered':3,'interruptedCounts':interrupted['counts'],
        'ownedWorkerExitedOnStdinEof':True,'workerExitObservedSeconds':worker_exit_seconds,'recovery':audit}
(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
