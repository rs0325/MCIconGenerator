#!/usr/bin/env python3
"""境界版のcold-cache/warm-cache native全件描画を一workerで測定する。"""
import atexit
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--distribution',type=Path,required=True)
parser.add_argument('--source-commit',required=True)
parser.add_argument('--core-commit',required=True)
parser.add_argument('--core-sha256',required=True)
parser.add_argument('--worker-commit',required=True)
parser.add_argument('--worker-sha256',required=True)
parser.add_argument('--image-id',required=True)
parser.add_argument('--runtime-description',required=True)
parser.add_argument('--vm-description',required=True)
parser.add_argument('--warm-runs',type=int,default=2)
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:0.1.0-preview.1')
args=parser.parse_args()
if not 1<=args.warm_runs<=5:parser.error('warm-run数は1〜5を指定してください')
out=args.out.resolve()
if out.exists():parser.error('--outには未使用の新しいディレクトリを指定してください。再実行は別outを使います')
def worker_status(timeout=15):
    try:result=subprocess.run(['docker','--context',args.docker_context,'ps','--filter','name=mcicon-worker-','--format','{{.Names}}'],capture_output=True,text=True,timeout=timeout)
    except subprocess.TimeoutExpired as error:
        stdout=error.stdout.decode() if isinstance(error.stdout,bytes) else error.stdout or ''
        stderr=error.stderr.decode() if isinstance(error.stderr,bytes) else error.stderr or ''
        return {'queryError':'timeout','returnCode':None,'timeoutSeconds':timeout,'stdout':stdout,'stderr':stderr}
    if result.returncode:return {'queryError':result.stderr.strip(),'returnCode':result.returncode}
    return {'names':result.stdout.splitlines()}
def terminate_probe_group(process,term_timeout=30,kill_timeout=15):
    try:os.killpg(process.pid,signal.SIGTERM)
    except ProcessLookupError:pass
    try:
        stdout,stderr=process.communicate(timeout=term_timeout)
        return stdout,stderr,{'sigtermSent':True,'sigkillSent':False,'pipeDrainTimedOut':False,'termWaitSeconds':term_timeout}
    except subprocess.TimeoutExpired:
        try:os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError:pass
        try:
            stdout,stderr=process.communicate(timeout=kill_timeout)
            return stdout,stderr,{'sigtermSent':True,'sigkillSent':True,'pipeDrainTimedOut':False,
                'termWaitSeconds':term_timeout,'killWaitSeconds':kill_timeout,'processReturnCode':process.returncode}
        except subprocess.TimeoutExpired as error:
            stdout=error.stdout.decode() if isinstance(error.stdout,bytes) else error.stdout or ''
            stderr=error.stderr.decode() if isinstance(error.stderr,bytes) else error.stderr or ''
            return stdout,stderr,{'sigtermSent':True,'sigkillSent':True,'pipeDrainTimedOut':True,
                'termWaitSeconds':term_timeout,'killWaitSeconds':kill_timeout,'processReturnCode':process.poll()}
def interrupt_handler(signum,_frame):
    raise KeyboardInterrupt(f'受信signal={signum}')
signal.signal(signal.SIGINT,interrupt_handler)
signal.signal(signal.SIGTERM,interrupt_handler)
source_distribution=args.distribution.resolve()
if not source_distribution.is_dir():parser.error('--distributionがCLI配布物ディレクトリではありません')
core_source=source_distribution/'lib/core-0.1.0-SNAPSHOT.jar'
worker_source=source_distribution/'worker/worker-agent.jar'
if not core_source.is_file() or not worker_source.is_file():parser.error('CLI配布物にcoreまたはworker agentがありません')
hash_file=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
if hash_file(core_source)!=args.core_sha256:parser.error('core SHA-256が指定値と一致しません')
if hash_file(worker_source)!=args.worker_sha256:parser.error('worker agent SHA-256が指定値と一致しません')
out.mkdir(parents=True);classes=out/'classes';classes.mkdir()
distribution=out/'cli';shutil.copytree(source_distribution,distribution)
if hash_file(distribution/'lib/core-0.1.0-SNAPSHOT.jar')!=args.core_sha256 or hash_file(distribution/'worker/worker-agent.jar')!=args.worker_sha256:
    parser.error('snapshot後のcore/worker SHA-256が指定値と一致しません')
classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/NativeBenchmarkProbe.java')],check=True)
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py')
audit_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit_module)
results=[]
run_sources=[Path(__file__).resolve(),Path(__file__).with_name('qa')/'NativeBenchmarkProbe.java',repo/'scripts/audit-export.py']
identity={'sourceCommit':args.source_commit,'coreCommit':args.core_commit,'coreSha256':hash_file(distribution/'lib/core-0.1.0-SNAPSHOT.jar'),
    'workerCommit':args.worker_commit,'workerSha256':hash_file(distribution/'worker/worker-agent.jar'),'imageId':args.image_id,
    'runtime':args.runtime_description,'vm':args.vm_description,'dockerContext':args.docker_context,'dockerImage':args.docker_image,
    'javaVersion':subprocess.run(['java','-version'],capture_output=True,text=True,check=True).stderr.splitlines()[0],
    'harnessSha256':{str(path.relative_to(repo)):hash_file(path) for path in run_sources}}
def write_report():
    (out/'report.json').write_text(json.dumps({'identity':identity,'runs':results,'coldCacheWasEmpty':True,
        'coldIncludesOfficialHttps':True,'warmOffline':True,'warmUsesFreshOutput':True,
        'memorySample':'最後のicon後、worker shutdown前のVmHWMとcontainer memory.peak',
        'osPageCacheCleared':False,'performanceGuarantee':False},ensure_ascii=False,indent=2)+'\n')
def record_unfinished():
    changed=False
    for record in results:
        if record['status']=='running':
            record.update(status='interrupted',failure='プロセス終了時点でrunが未完了')
            changed=True
    if changed:write_report()
atexit.register(record_unfinished)
initial_workers=worker_status()
if 'queryError' in initial_workers or initial_workers['names']:
    results.append({'phase':'preflight-worker-check','status':'failed','workerStatus':initial_workers,
        'failure':'Docker worker状態を取得できない、または既存MCIcon workerが稼働中です'})
    write_report()
    raise RuntimeError('Docker worker事前確認に失敗。private reportを保持し、native計測は開始しません')

for version in ('1.13','26.3'):
    version_out=out/version;version_out.mkdir()
    cache=version_out/'cache';cache.mkdir()
    if any(cache.iterdir()):raise RuntimeError('新規cacheが空ではありません: '+version)
    cold_manifest=None;cold_metadata=None
    for run in ['cold',*(f'warm-{i+1}' for i in range(args.warm_runs))]:
        output=version_out/run
        record={'version':version,'run':run,'cacheEmptyAtStart':run=='cold','offline':run!='cold','status':'running'}
        results.append(record);write_report()
        process=None
        try:
            process=subprocess.Popen(['java','-cp',str(classes)+os.pathsep+classpath,'NativeBenchmarkProbe',version,str(cache),str(output),str(run!='cold').lower(),
                str(distribution/'worker/worker-agent.jar'),args.docker_context,args.docker_image],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
            try:
                stdout,stderr=process.communicate(timeout=600)
                cleanup={'timedOut':False}
            except KeyboardInterrupt as error:
                stdout,stderr,cleanup=terminate_probe_group(process)
                (version_out/(run+'.probe.stdout')).write_text(stdout)
                (version_out/(run+'.stderr.log')).write_text(stderr)
                record.update(status='interrupted',failure=str(error),returnCode=process.returncode,processCleanup=cleanup,
                    remainingWorkers=worker_status())
                write_report();raise
            except subprocess.TimeoutExpired:
                stdout,stderr,cleanup=terminate_probe_group(process)
                cleanup['timedOut']=True
            result=process.returncode
        except Exception as error:
            record.update(status='failed',failure=f'{type(error).__name__}: {error}')
            record['remainingWorkers']=worker_status()
            write_report();raise
        (version_out/(run+'.probe.stdout')).write_text(stdout)
        (version_out/(run+'.stderr.log')).write_text(stderr)
        record.update(returnCode=result,processCleanup=cleanup,remainingWorkers=worker_status())
        if cleanup['timedOut']:
            record.update(status='failed',failure='Java probe timeout (600 seconds)')
            write_report();raise RuntimeError(f'{version}/{run}: timeout。process cleanupとworker状態を記録し、再試行は開始していません')
        if result:
            record.update(status='failed',failure='Java probeが非zeroを返しました')
            write_report();raise RuntimeError(f'{version}/{run}: Java probe失敗。private logとreportを保持しました')
        if 'queryError' in record['remainingWorkers'] or record['remainingWorkers']['names']:
            record.update(status='failed',failure='実行後にDocker workerが残存または状態確認に失敗',remainingWorkers=record['remainingWorkers'])
            write_report();raise RuntimeError(f'{version}/{run}: 実行後のworker cleanup確認が必要です。containerは停止していません')
        try:
            report=json.loads(stdout);report['run']=run
            manifest=output/'java'/version/'icons'/'manifest.json'
            audit=audit_module.audit(manifest,compare=cold_manifest if run!='cold' else None,require_all=True)
            if audit['failures']:raise RuntimeError('PNG監査またはcold出力との比較に失敗: '+str(audit['failures']))
            report['audit']=audit
            metadata=json.loads(manifest.read_text())
            report.update(agentSha256=metadata['settings']['agentSha256'],mappingsSha256=metadata['settings']['mappingsSha256'],
                settingsDigest=metadata['settingsDigest'],sourceSha256=metadata['sourceSha256'],imageId=metadata['environment']['imageId'])
            if report['imageId']!=args.image_id:raise RuntimeError('実行image IDが指定値と一致しません: '+report['imageId'])
            if run=='cold':
                cold_manifest=manifest
                cold_metadata={key:report[key] for key in ('agentSha256','mappingsSha256','settingsDigest','sourceSha256','imageId')}
            elif {key:report[key] for key in cold_metadata}!=cold_metadata:
                raise RuntimeError('cold/warmの入力またはrenderer識別値が異なります')
            files=[path for path in cache.rglob('*') if path.is_file() and not path.relative_to(cache).parts[0].startswith('worker-')]
            report['preparedCacheBytes']=sum(path.stat().st_size for path in files);report['preparedCacheFiles']=len(files)
        except Exception as error:
            record.update(status='failed',failure=f'{type(error).__name__}: {error}')
            write_report();raise
        record.update(status='succeeded',probe=report)
        write_report()
        print(version,run,round(report['elapsedSeconds'],2),'秒',report['workerVmHwmBytesBeforeShutdown'],'bytes',flush=True)
