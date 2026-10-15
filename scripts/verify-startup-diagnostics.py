#!/usr/bin/env python3
"""専用cacheのcold/warm起動を計測する診断QA。native実行は割当済み枠で行う。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time

repo=Path(__file__).resolve().parents[1]
JFR_SETTINGS="""<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="MCIcon startup exceptions" description="QA cause保存" provider="MCIconGenerator">
 <event name="jdk.JavaExceptionThrow"><setting name="enabled">true</setting><setting name="stackTrace">true</setting></event>
 <event name="jdk.JavaErrorThrow"><setting name="enabled">true</setting><setting name="stackTrace">true</setting></event>
</configuration>
"""

def monitor(process,folder,docker,cache,delayed,stop):
    owned=None;start=time.monotonic();last_dump=-1
    snapshots=[45,120,165] if delayed else [1,5,15,45,120,165]
    with (folder/'samples.jsonl').open('w') as log:
        while not stop.is_set() and process.poll() is None:
            record={'wallNs':time.time_ns(),'monotonicNs':time.monotonic_ns(),'elapsedSeconds':round(time.monotonic()-start,3)}
            trace=folder/'docker-run.json'
            if trace.exists():
                try:
                    lifecycle=json.loads(trace.read_text());record['dockerPid']=lifecycle['dockerPid'];record['runDirectory']=lifecycle['runDirectory'];owned=lifecycle['containerName']
                except (ValueError,KeyError):pass
            result=subprocess.run([*docker,'ps','--filter','name=mcicon-worker-','--format','{{.ID}}'],capture_output=True,text=True,timeout=10)
            if stop.is_set():break
            record['concurrentWorkers']=len(result.stdout.splitlines()) if result.returncode==0 else None
            if owned:
                inspected=subprocess.run([*docker,'inspect',owned],capture_output=True,text=True,timeout=10)
                if stop.is_set():break
                if inspected.returncode==0:
                    info=json.loads(inspected.stdout)[0]
                    if not any(m.get('Source')==str(cache) for m in info['Mounts']):raise AssertionError('専用cache mountが一致しません')
                    record['containerId']=info['Id'];record['state']=info['State']
                    stats=subprocess.run([*docker,'stats','--no-stream','--format','{{json .}}',owned],capture_output=True,text=True,timeout=10)
                    if stop.is_set():break
                    record['stats']=json.loads(stats.stdout) if stats.returncode==0 and stats.stdout.strip() else None
            elapsed=time.monotonic()-start
            due=max((i for i,t in enumerate(snapshots) if elapsed>=t),default=-1)
            if due>last_dump:
                last_dump=due
                with (folder/('host-threads-'+str(snapshots[due])+'.log')).open('wb') as output:
                    try:
                        probe=subprocess.run(['jcmd',str(process.pid),'Thread.print'],stdout=output,stderr=subprocess.STDOUT,timeout=15)
                        record['hostThreadDumpExitCode']=probe.returncode
                    except subprocess.TimeoutExpired:record['hostThreadDumpTimedOut']=True
                if stop.is_set():break
                if owned:
                    with (folder/('game-threads-'+str(snapshots[due])+'.log')).open('wb') as output:
                        try:
                            probe=subprocess.run([*docker,'exec',owned,'/opt/java/java21/bin/jcmd','1','Thread.print'],stdout=output,stderr=subprocess.STDOUT,timeout=15)
                            record['gameThreadDumpExitCode']=probe.returncode
                        except subprocess.TimeoutExpired:record['gameThreadDumpTimedOut']=True
            log.write(json.dumps(record,ensure_ascii=False)+'\n');log.flush()
            stop.wait(.5)


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--cache',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--version',default='1.21.4')
    p.add_argument('--id',default='netherite_ingot')
    p.add_argument('--runs',type=int,default=2)
    p.add_argument('--warm-cache',action='store_true',help='同じ専用cacheのゲーム起動後に開始する対照')
    p.add_argument('--delayed-observation',action='store_true',help='host JFRを使わず、45秒を超えた起動だけthread dumpを採る')
    p.add_argument('--docker-context',default='colima-mcicon')
    p.add_argument('--docker-image',default='sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf')
    p.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
    p.add_argument('--compare',type=Path,help='別出力の同binary/image正常基準manifest')
    p.add_argument('--stage-agent',type=Path,help='旧1.21.4専用のQA境界記録agent（追加の観測条件）')
    p.add_argument('--datafixer-gate-agent',type=Path,help='最適化taskを明示解除まで保留する旧1.21.4専用QA agent')
    a=p.parse_args();out=a.out.resolve();cache=a.cache.resolve();out.mkdir(parents=True,exist_ok=False)
    if a.datafixer_gate_agent and not a.stage_agent:p.error('gateの制御には--stage-agentが必要です')
    if a.runs<1:p.error('--runs は1以上です')
    used=bool(list(cache.glob('worker-*')))
    if used!=a.warm_cache:p.error('初回はゲーム未起動cache、--warm-cache では起動済み専用cacheを指定してください')
    distribution=out/'cli';shutil.copytree(a.cli.resolve().parent.parent,distribution)
    private=out/'bin';private.mkdir();shutil.copyfile(repo/'scripts/qa/trace-docker.py',private/'docker');(private/'docker').chmod(0o755)
    docker=['docker','--context',a.docker_context]
    settings=out/'exceptions.jfc';settings.write_text(JFR_SETTINGS)
    report={'debugEnabled':True,'hostJfrEnabled':not a.delayed_observation,'threadDumpSeconds':[45,120,165] if a.delayed_observation else [1,5,15,45,120,165],
            'scope':'専用cache初回gameをcold、同cache後続をwarmと呼ぶ。OS pagecacheは消去しない。wrapper/監視の観測負荷がある。host JFRの有無を別記録する。','jobs':[],
            'coreSha256':hashlib.sha256(next((distribution/'lib').glob('core-*.jar')).read_bytes()).hexdigest(),
            'workerSha256':hashlib.sha256((distribution/'worker/worker-agent.jar').read_bytes()).hexdigest(),
            'sourceSha256':hashlib.sha256((cache/'versions'/a.version/'client.jar').read_bytes()).hexdigest(),
            'harnessSourcesSha256':{str(path.relative_to(repo)):hashlib.sha256(path.read_bytes()).hexdigest() for path in (Path(__file__).resolve(),repo/'scripts/qa/trace-docker.py')}}
    try:
        report['imageId']=subprocess.check_output([*docker,'image','inspect',a.docker_image,'--format','{{.Id}}'],text=True,timeout=15).strip()
        report['effectiveJava']=subprocess.run(['java','-version'],capture_output=True,text=True,check=True,timeout=10).stderr
    except (OSError,subprocess.TimeoutExpired,subprocess.CalledProcessError) as error:
        report['initializationFailure']=type(error).__name__
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        raise
    real_docker=shutil.which('docker')
    spec=importlib.util.spec_from_file_location('audit',repo/'scripts/audit-export.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    for index in range(a.runs):
        folder=out/str(index);folder.mkdir();start=time.monotonic();start_wall=time.time_ns()
        env=dict(os.environ,PATH=str(private)+os.pathsep+os.environ['PATH'],MCICON_REAL_DOCKER=real_docker,MCICON_STARTUP_TRACE=str(folder))
        env.pop('MCICON_STARTUP_STAGE_AGENT',None)
        env.pop('MCICON_STARTUP_DATAFIXER_GATE',None)
        if a.stage_agent:env['MCICON_STARTUP_STAGE_AGENT']=str(a.stage_agent.resolve(strict=True))
        if a.datafixer_gate_agent:env['MCICON_STARTUP_DATAFIXER_GATE']=str(a.datafixer_gate_agent.resolve(strict=True))
        recording='-XX:StartFlightRecording=filename='+str(folder/'host.jfr')+',settings='+str(settings)+',dumponexit=true -Xlog:jfr+startup=off'
        if not a.delayed_observation:env['JAVA_OPTS']=os.environ.get('JAVA_OPTS','')+' '+recording
        command=[str(distribution/'bin/mcicon'),'--debug','render','--version',a.version,'--size','64','--allow-experimental','--offline','--cache',str(cache),'--out',str(folder/'output'),'--worker-jar',str(distribution/'worker/worker-agent.jar'),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--json','--id',a.id]
        with (folder/'events.jsonl').open('wb') as event_log,(folder/'stdout.json').open('wb') as stdout,(folder/'stderr.log').open('wb') as stderr:
            events=subprocess.Popen([*docker,'events','--filter','type=container','--format','{{json .}}'],stdout=event_log,stderr=subprocess.DEVNULL)
            process=subprocess.Popen(command,env=env,stdout=stdout,stderr=stderr)
            errors=[]
            def watch():
                try:monitor(process,folder,docker,cache,a.delayed_observation,stop)
                except Exception as error:errors.append(str(error));(folder/'monitor-error.log').write_text(repr(error)+'\n')
            stop=threading.Event()
            thread=threading.Thread(target=watch);thread.start();signal_sent=None
            try:
                process.wait(timeout=300)
            except subprocess.TimeoutExpired:
                signal_sent='SIGTERM';process.terminate()
                try:process.wait(timeout=20)
                except subprocess.TimeoutExpired:signal_sent='SIGKILL';process.kill();process.wait(timeout=10)
            finally:
                stop.set()
                events.terminate()
                try:events.wait(timeout=10)
                except subprocess.TimeoutExpired:events.kill();events.wait(timeout=2)
                thread.join(20)
                if thread.is_alive():
                    report['monitorFailure']='監視threadが終了期限を超過しました'
                    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
                    raise RuntimeError(report['monitorFailure'])
        elapsed=time.monotonic()-start
        if (folder/'host.jfr').exists():
            with (folder/'host-exceptions.json').open('wb') as output:
                extracted=subprocess.run(['jfr','print','--json','--events','jdk.JavaExceptionThrow,jdk.JavaErrorThrow',str(folder/'host.jfr')],stdout=output,stderr=subprocess.PIPE,timeout=30)
                if extracted.returncode!=0:errors.append('JFR exceptionの抽出に失敗しました')
        elif not a.delayed_observation:errors.append('host JFRが保存されませんでした')
        trace=folder/'docker-run.json';lifecycle=json.loads(trace.read_text()) if trace.exists() else {}
        owned=lifecycle.get('containerName');samples=[json.loads(line) for line in (folder/'samples.jsonl').read_text().splitlines()]
        container_ids={s['containerId'] for s in samples if 'containerId' in s}
        observed=[json.loads(line) for line in (folder/'events.jsonl').read_text().splitlines() if line]
        relevant=[e for e in observed if e['Actor']['ID'] in container_ids]
        job={'debugEnabled':True,'condition':'warm' if a.warm_cache or index>0 else 'cold','cliPid':process.pid,'startedWallNs':start_wall,'finishedWallNs':time.time_ns(),'elapsedSeconds':round(elapsed,3),'exitCode':process.returncode,'harnessSignal':signal_sent,'monitorErrors':errors,'lifecycle':lifecycle,'samples':len(samples),'maxConcurrentWorkers':max((s.get('concurrentWorkers') or 0 for s in samples),default=0),'ownedContainerIds':sorted(container_ids),'events':relevant,'passed':False}
        if owned:
            inspected=subprocess.run([*docker,'inspect',owned],capture_output=True,text=True,timeout=10)
            job['ownedContainerRemoved']=inspected.returncode==1 and 'no such' in inspected.stderr.lower()
        run=Path(lifecycle['runDirectory']) if 'runDirectory' in lifecycle else None
        if run:
            job['snapshotRemoved']=not (run/'worker-agent.jar').exists();job['temporaryIconsRemoved']=not list(run.glob('icon-*.png'))
            if (run/'worker.log').exists():shutil.copyfile(run/'worker.log',folder/'worker.log')
            if (run/'startup-stages.tsv').exists():shutil.copyfile(run/'startup-stages.tsv',folder/'startup-stages.tsv')
        if process.returncode==0:
            manifest=folder/'output/java'/a.version/'icons/manifest.json';comparison=out/'0/output/java'/a.version/'icons/manifest.json' if index else a.compare
            job['audit']=audit.audit(manifest,compare=comparison)
            job['passed']=not errors and not signal_sent and not job['audit']['failures'] and (not comparison or job['audit']['comparisonVerified']) and job.get('ownedContainerRemoved',False) and job.get('snapshotRemoved',False) and job.get('temporaryIconsRemoved',False) and 'readyMonotonicNs' in lifecycle
        report['jobs'].append(job);(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(index,job['condition'],'PASS' if job['passed'] else 'FAIL',flush=True)
        if not job['passed']:raise SystemExit(1)

if __name__=='__main__':main()
