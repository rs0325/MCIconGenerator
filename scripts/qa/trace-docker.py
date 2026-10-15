#!/usr/bin/env python3
"""QA専用Docker wrapper。stdout bytesを転送し、起動段階だけprivateに記録する。"""
import json
import hashlib
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import time


def main():
    arguments=sys.argv[1:]
    root=Path(os.environ['MCICON_STARTUP_TRACE'])
    name=arguments[arguments.index('--name')+1] if '--name' in arguments else ''
    mounts=[arguments[i+1] for i,value in enumerate(arguments[:-1]) if value=='-v']
    snapshot=next((Path(m.split(':')[0]) for m in mounts if m.endswith(':/mcicon/worker.jar:ro')),None)
    run='run' in arguments and name.startswith('mcicon-worker-') and snapshot is not None
    record={'arguments':arguments,'wrapperPid':os.getpid(),'parentPid':os.getppid(),
            'startedWallNs':time.time_ns(),'startedMonotonicNs':time.monotonic_ns()}
    if run:
        record['containerName']=name
        record['mounts']=mounts
        record['runDirectory']=str(snapshot.parent)
        if agent := os.environ.get('MCICON_STARTUP_STAGE_AGENT'):
            source=Path(agent).resolve(strict=True)
            target=snapshot.parent/'startup-stage.jar'
            shutil.copyfile(source,target)
            previous='-javaagent:/mcicon/worker.jar='
            indices=[i for i,value in enumerate(arguments) if value.startswith(previous)]
            if len(indices)!=1:raise ValueError('旧worker agentの引数が一意ではありません')
            record['originalArguments']=list(arguments)
            arguments.insert(indices[0]+1,'-javaagent:/mcicon/cache/'+snapshot.parent.name+'/startup-stage.jar=/mcicon/cache/'+snapshot.parent.name+'/startup-stages.tsv')
            record['stageAgentSha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    destination=root/('docker-run.json' if run else 'docker-call-'+str(os.getpid())+'.json')
    def save():destination.write_text(json.dumps(record,ensure_ascii=True,indent=2)+'\n')
    save()
    process=subprocess.Popen([os.environ['MCICON_REAL_DOCKER'],*arguments],stdin=sys.stdin.buffer,stdout=subprocess.PIPE,stderr=sys.stderr.buffer)
    record['dockerPid']=process.pid;save()
    def interrupt(signum,_):
        record['wrapperSignal']=signum;save();process.send_signal(signum)
    signal.signal(signal.SIGTERM,interrupt);signal.signal(signal.SIGINT,interrupt)
    pending=b''
    raw=(root/'protocol.stdout').open('wb') if run else None
    try:
        while True:
            data=os.read(process.stdout.fileno(),65536)
            if not data:break
            if raw:raw.write(data);raw.flush()
            if run:
                if 'firstStdoutMonotonicNs' not in record:
                    record['firstStdoutWallNs']=time.time_ns();record['firstStdoutMonotonicNs']=time.monotonic_ns();save()
                pending+=data
                while b'\n' in pending:
                    line,pending=pending.split(b'\n',1)
                    try:
                        message=json.loads(line)
                        if not isinstance(message,dict):raise ValueError("protocolがJSON objectではありません")
                    except (ValueError,UnicodeDecodeError):
                        record['invalidProtocolLines']=record.get('invalidProtocolLines',0)+1;save();continue
                    if message.get('type')=='ready':
                        record.update(readyWallNs=time.time_ns(),readyMonotonicNs=time.monotonic_ns(),protocolVersion=message.get('protocolVersion'),renderer=message.get('renderer'));save()
            sys.stdout.buffer.write(data);sys.stdout.buffer.flush()
        code=process.wait();record.update(exitCode=code,finishedWallNs=time.time_ns(),finishedMonotonicNs=time.monotonic_ns());save()
    except BrokenPipeError:
        record['forwardingBrokenPipe']=True;save();process.terminate()
        try:process.wait(timeout=2)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=2)
        code=1
    finally:
        if raw:raw.close()
        process.stdout.close()
    return code

if __name__=='__main__':raise SystemExit(main())
