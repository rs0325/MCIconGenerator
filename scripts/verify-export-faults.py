#!/usr/bin/env python3
"""Local raw export fault acceptance. Requires built CLI, JDK21, cached 1.13 and Docker.

The output directory must be new. No game assets are written to the repository.
"""
import argparse
import fcntl
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
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
out=args.out.resolve();cache=args.cache.resolve()
if out.exists():parser.error('--out must be a new directory')
out.mkdir(parents=True)
distribution=out/'cli';shutil.copytree(repo/'cli/build/install/mcicon',distribution)
cli=distribution/'bin/mcicon'
classes=out/'classes';classes.mkdir()
classpath=os.pathsep.join(str(p) for p in sorted((distribution/'lib').glob('*.jar')))
subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/ExportFaultProbe.java'),str(repo/'scripts/qa/AtomicCrashProbe.java')],check=True)
actor=['java','-cp',str(classes)+os.pathsep+classpath,'ExportFaultProbe']
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py')
audit_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit_module)
results={}

def extract(output):
    return [str(cli),'extract','--version','1.13','--all','--cache',str(cache),'--out',str(output),'--offline','--json']

for mode in ('cancel','kill'):
    output=out/mode
    if mode=='cancel':
        result=subprocess.run([*actor,'cancel',str(cache),str(output)],capture_output=True,text=True,timeout=120)
        assert result.returncode==0,result.stderr
    else:
        error_log=(out/'kill.log').open('w')
        process=subprocess.Popen([*actor,'hold',str(cache),str(output)],stdout=subprocess.PIPE,stderr=error_log,text=True)
        try:
            selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
            assert selector.select(120),'Probe did not reach the checkpoint'
            assert process.stdout.readline().strip()=='READY_FOR_KILL'
            os.kill(process.pid,signal.SIGKILL);assert process.wait(10)==-signal.SIGKILL
        finally:
            if process.poll() is None:process.kill();process.wait()
            process.stdout.close();selector.close();error_log.close()
    manifest=output/'java/1.13/raw/manifest.json'
    interrupted=json.loads(manifest.read_text())
    assert not interrupted['complete'] and interrupted['counts']['failed']>0
    result=subprocess.run(extract(output),capture_output=True,text=True,timeout=120)
    assert result.returncode==0,result.stderr
    audit=audit_module.audit(manifest,source_jar=cache/'versions/1.13/client.jar')
    assert not audit['failures'],audit
    assert not list(output.rglob('*.part'))
    results[mode]={'interruptedCounts':interrupted['counts'],'recovery':audit,'temporaryFiles':0}
    print(mode,'PASS',audit['audited'],flush=True)

# POSIX record locks match Java FileChannel locks across real processes.
output=out/'busy';base=output/'java/1.13/raw';base.mkdir(parents=True)
with (base/'.export.lock').open('w') as lock:
    fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    result=subprocess.run(extract(output),capture_output=True,text=True,timeout=120)
    assert result.returncode==1 and 'OUTPUT_BUSY' in result.stderr,(result.returncode,result.stdout,result.stderr)
    assert not (base/'manifest.json').exists()
results['outputBusy']={'exitCode':result.returncode,'manifestWritten':False}
print('outputBusy PASS',flush=True)

# Kill while the real atomic writer owns a temporary file; recover it next job.
output=out/'atomic-kill'
atomic_actor=['java','-Xmx512m','-cp',str(classes)+os.pathsep+classpath,'AtomicCrashProbe']
process=subprocess.Popen([*atomic_actor,'write',str(output)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
try:
    selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
    assert selector.select(30),'Atomic actor did not start'
    assert process.stdout.readline().strip()=='READY_FOR_ATOMIC_WRITE'
    root=output/'java/synthetic/raw';deadline=time.monotonic()+10
    while not list(root.rglob('.mcicon-*.part')):
        assert process.poll() is None,'Atomic actor finished before interruption'
        assert time.monotonic()<deadline,'Atomic temporary file was not observed'
        time.sleep(0.001)
    os.kill(process.pid,signal.SIGKILL);assert process.wait(10)==-signal.SIGKILL
    orphaned=len(list(root.rglob('.mcicon-*.part')));assert orphaned>0
finally:
    if process.poll() is None:process.kill();process.wait()
    process.stdout.close();process.stderr.close();selector.close()
subprocess.run([*atomic_actor,'resume',str(output)],check=True,timeout=30)
audit=audit_module.audit(root/'manifest.json',source_jar=output/'fixture.jar')
assert not audit['failures'] and not list(root.rglob('*.part'))
results['atomicWriteKill']={'orphanedTemporaryFiles':orphaned,'temporaryFilesAfterResume':0,'synthetic':True,'recovery':audit}
print('atomicWriteKill PASS',flush=True)

# Exhaust a separate container tmpfs; never fill the host filesystem.
disk_output=out/'disk-full';disk_output.mkdir()
command=['docker','--context',args.docker_context,'run','--rm','--network','none','--platform','linux/amd64',
         '--entrypoint','/bin/sh','--tmpfs','/output:rw,size=1m',
         '-v',str(distribution)+':/cli:ro','-v',str(classes)+':/classes:ro','-v',str(cache)+':/cache','-v',str(disk_output)+':/recovery',args.docker_image,'-c',
         'export JAVA_HOME=/opt/java/java21; export PATH="$JAVA_HOME/bin:$PATH"; '
         'java -cp "/classes:/cli/lib/*" ExportFaultProbe normal /cache /output >/tmp/result.json 2>/tmp/error.log; '
         'code=$?; cat /tmp/error.log; cat /tmp/result.json; '
         'test "$code" -ne 0 && test -z "$(find /output -name \'*.part\' -print)" || exit 10; '
         'test -f /output/java/1.13/raw/manifest.json || exit 11; '
         'cat /output/java/1.13/raw/manifest.json; cp -R /output/java /recovery/; echo ENOSPC_CLEANUP_PASSED']
started=time.monotonic();result=subprocess.run(command,capture_output=True,text=True,timeout=120)
(out/'disk-full.log').write_text(result.stdout+result.stderr)
assert result.returncode==0 and 'ENOSPC_CLEANUP_PASSED' in result.stdout,(result.returncode,result.stdout,result.stderr)
assert 'No space left on device' in result.stdout+result.stderr
start=result.stdout.index('{');end=result.stdout.rindex('}')+1
interrupted=json.loads(result.stdout[start:end])
assert not interrupted['complete'] and interrupted['counts']['succeeded']>0 and interrupted['counts']['failed']>0
result=subprocess.run(extract(disk_output),capture_output=True,text=True,timeout=120)
assert result.returncode==0,result.stderr
audit=audit_module.audit(disk_output/'java/1.13/raw/manifest.json',source_jar=cache/'versions/1.13/client.jar')
assert not audit['failures'],audit
results['diskFull']={'outputTmpfsBytes':1048576,'temporaryFiles':0,'exitWasNonzero':True,'interruptedCounts':interrupted['counts'],'rootCause':'No space left on device','recovery':audit,'elapsedSeconds':round(time.monotonic()-started,2)}
print('diskFull PASS',flush=True)
(out/'report.json').write_text(json.dumps(results,indent=2)+'\n')
