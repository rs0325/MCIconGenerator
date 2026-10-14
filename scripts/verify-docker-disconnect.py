#!/usr/bin/env python3
"""専用Unix socket/contextでDocker attachを切断する。daemonと他containerを停止しない。"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import selectors
import shutil
import socket
import subprocess
import tempfile
import threading
import time

repo=Path(__file__).resolve().parents[1]

class Proxy:
    def __init__(self,path,target):
        self.target=target
        self.arm=threading.Event();self.cut=threading.Event();self.stop=threading.Event()
        self.listener=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        self.listener.bind(str(path));self.listener.listen();self.listener.settimeout(.2)
        self.thread=threading.Thread(target=self.accept,daemon=True);self.thread.start()
    def accept(self):
        while not self.stop.is_set():
            try:client,_=self.listener.accept()
            except socket.timeout:continue
            except OSError:return
            server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);server.connect(self.target)
            threading.Thread(target=self.relay,args=(client,server),daemon=True).start()
    def relay(self,client,server):
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(client,selectors.EVENT_READ);selector.register(server,selectors.EVENT_READ)
                pending=b''
                while not self.stop.is_set():
                    for key,_ in selector.select(.2):
                        data=key.fileobj.recv(65536)
                        if not data:return
                        if key.fileobj is client:
                            pending=(pending+data)[-65536:]
                            if self.arm.is_set() and b'"command":"render"' in pending:
                                self.arm.clear();self.cut.set();return
                        (server if key.fileobj is client else client).sendall(data)
        except (ConnectionError,OSError):pass
        finally:
            for connection in (client,server):
                try:connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                connection.close()
    def close(self):
        self.stop.set();self.listener.close();self.thread.join(1)

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--cache',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--docker-context',default='colima-mcicon')
    p.add_argument('--docker-image',default='mcicon-render:0.1.0-preview.1')
    a=p.parse_args();out=a.out.resolve();cache=a.cache.resolve();out.mkdir(parents=True,exist_ok=False)
    docker=['docker','--context',a.docker_context]
    endpoint=json.loads(subprocess.check_output([*docker,'context','inspect'],text=True))[0]['Endpoints']['docker']['Host']
    if not endpoint.startswith('unix://'):p.error('Unix socketのcontextだけを使用します')
    distribution=out/'cli';shutil.copytree(repo/'cli/build/install/mcicon',distribution)
    agent=distribution/'worker/worker-agent.jar'
    classes=out/'classes';classes.mkdir()
    classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
    source=repo/'scripts/qa/DockerDisconnectProbe.java'
    subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(source)],check=True)
    actor=['java','-cp',str(classes)+os.pathsep+classpath,'DockerDisconnectProbe']
    spec=importlib.util.spec_from_file_location('audit',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    report={'daemonStopped':False,'otherContainersStopped':False,'version':'1.13','size':64,
            'imageId':subprocess.check_output([*docker,'image','inspect',a.docker_image,'--format','{{.Id}}'],text=True).strip(),
            'coreSha256':hashlib.sha256(next((distribution/'lib').glob('core-*.jar')).read_bytes()).hexdigest(),
            'agentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),
            'harnessSourcesSha256':{str(path.relative_to(repo)):hashlib.sha256(path.read_bytes()).hexdigest() for path in (Path(__file__).resolve(),source)}}
    def save(): (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    save()
    baseline=out/'baseline'
    with (out/'baseline.log').open('w') as log:
        normal=subprocess.run([*actor,'normal',str(cache),str(baseline),str(agent),a.docker_context,a.docker_image],stdout=log,stderr=log,timeout=240)
    assert normal.returncode==0,'正常基準の生成に失敗しました'
    manifest=baseline/'java/1.13/icons/manifest.json'
    baseline_data=json.loads(manifest.read_text());report['sourceSha256']=baseline_data['sourceSha256']
    report['baselineCounts']=baseline_data['counts'];save()
    output=out/'interrupted';owned=None;proxy=None;process=None
    # socketのOS上限に収まる専用temporary。Dockerのglobal contextを変更しない。
    with tempfile.TemporaryDirectory(prefix='mcicon-disconnect-') as temporary:
        private=Path(temporary);config=private/'config';config.mkdir()
        env=dict(os.environ,DOCKER_CONFIG=str(config));context='mcicon-disconnect'
        proxy=Proxy(private/'docker.sock',endpoint.removeprefix('unix://'))
        subprocess.run(['docker','context','create',context,'--docker','host=unix://'+str(private/'docker.sock')],env=env,check=True,capture_output=True)
        old_runs=set(cache.glob('worker-*'));started=None;selector=None
        try:
            with (out/'fault.log').open('w') as log:
                process=subprocess.Popen([*actor,'fault',str(cache),str(output),str(agent),context,a.docker_image],env=env,stdout=subprocess.PIPE,stdin=subprocess.PIPE,stderr=log,text=True)
                selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
                assert selector.select(210),'切断checkpointに到達しませんでした'
                line=process.stdout.readline().strip();assert line.startswith('READY_FOR_DISCONNECT mcicon-worker-'),line
                owned=line.split()[1]
                # 既存raw FileLock受入と同じPOSIX record lockで、切断直前のicons所有を確認する。
                with (output/'java/1.13/icons/.export.lock').open('w') as lock:
                    try:fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                    except BlockingIOError:report['outputLockHeldBeforeCut']=True
                    else:raise AssertionError('出力lockを所有していません')
                proxy.arm.set();started=time.monotonic();process.stdin.write('\n');process.stdin.flush()
                assert proxy.cut.wait(10),'次のrender要求を切断しませんでした'
                code=process.wait(45);remainder=process.stdout.read();(out/'fault.stdout').write_text(line+'\n'+remainder)
                report.update(exitCode=code,jobFinishedSeconds=round(time.monotonic()-started,3),attachBothDirectionsClosed=True)
                save();assert code==3,'Futureが未完了のExportResultを返しませんでした'
                assert 'RESULT ' in remainder,'Future完了を観測していません'
                report['futureCompletedObserved']=True
            interrupted=json.loads((output/'java/1.13/icons/manifest.json').read_text())
            shutil.copyfile(output/'java/1.13/icons/manifest.json',out/'interrupted-manifest.json')
            entries=interrupted['entries'];report['interruptedCounts']=interrupted['counts']
            report['interruptedCodes']=[e['code'] for e in entries if e['status']=='failed']
            success=[e for e in entries if e['status']=='success']
            baseline_entries={e['id']:e for e in baseline_data['entries']}
            report['successKept']=len(success)==1 and all(e['sha256']==baseline_entries[e['id']]['sha256'] and hashlib.sha256((output/'java/1.13/icons'/e['path']).read_bytes()).hexdigest()==e['sha256'] for e in success)
            report['reservedPathsKept']=sum(e.get('code')=='NOT_RENDERED' for e in entries)==2
            inspection=subprocess.run([*docker,'container','inspect',owned],capture_output=True,text=True)
            report['daemonRespondingAfterCut']=subprocess.run([*docker,'info','--format','{{.ID}}'],capture_output=True,timeout=15).returncode==0
            report['ownedContainerRemoved']=inspection.returncode==1 and 'No such' in inspection.stderr and report['daemonRespondingAfterCut']
            runs=set(cache.glob('worker-*'))-old_runs
            report['ownedSnapshotsRemoved']=len(runs)==1 and not any((r/'worker-agent.jar').exists() or list(r.glob('icon-*.png')) for r in runs)
            report['workerLogsKept']=len(runs)==1 and all((r/'worker.log').is_file() for r in runs)
            with (output/'java/1.13/icons/.export.lock').open('w') as lock:fcntl.lockf(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            report['outputLockReleasedAfterCut']=True
            save()
            assert report['ownedContainerRemoved'],'専用containerが残っています'
            assert all(report[k] for k in ('successKept','reservedPathsKept','ownedSnapshotsRemoved','workerLogsKept'))
        finally:
            if process and process.poll() is None:process.kill();process.wait()
            if process:process.stdout.close();process.stdin.close()
            if selector:selector.close()
            proxy.close()
            if owned:subprocess.run([*docker,'rm','-f',owned],capture_output=True)
    with (out/'resume.log').open('w') as log:
        resumed=subprocess.run([*actor,'normal',str(cache),str(output),str(agent),a.docker_context,a.docker_image],stdout=log,stderr=log,timeout=240)
    report['resumeExitCode']=resumed.returncode;save();assert resumed.returncode==0
    recovery=audit.audit(output/'java/1.13/icons/manifest.json',compare=manifest)
    report['recovery']=recovery;report['temporaryFilesAfterResume']=len(list((output/'java').rglob('*.part')))
    report['passed']=not recovery['failures'] and recovery['comparisonVerified'] and report['temporaryFilesAfterResume']==0
    save();assert report['passed'],recovery
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':main()
