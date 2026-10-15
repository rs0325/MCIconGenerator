#!/usr/bin/env python3
"""旧失敗世代の起動診断を2系列で有限時間繰り返し、失敗世代を保全する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid

REPO = Path(__file__).resolve().parents[1]
IMAGE = 'sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf'


def group_actors(pid):
    result = subprocess.run(['ps', '-axo', 'pid=,pgid=,stat='], capture_output=True, text=True, check=True, timeout=5)
    return [int(row[0]) for line in result.stdout.splitlines()
            if len(row := line.split()) == 3 and int(row[1]) == pid and not row[2].startswith('Z')]


def wait_unreaped(process, seconds, stop, marker):
    end = time.monotonic() + seconds
    while True:
        state = subprocess.run(['ps', '-p', str(process.pid), '-o', 'stat='], capture_output=True, text=True, timeout=5)
        if state.returncode or not state.stdout.strip():
            raise RuntimeError('未reapの親状態を確認できない')
        if state.stdout.strip().startswith('Z'):
            return
        if stop.is_set() or marker.exists():
            raise RuntimeError('SERIES_STOP')
        if time.monotonic() >= end:
            raise RuntimeError('OUTER_DEADLINE')
        time.sleep(.2)


def reap_group(process):
    result = {'forced': False}
    # 親を未reapで保持している間だけ、このPGIDへsignalを送る。
    for sig, seconds in [(signal.SIGTERM, 5), (signal.SIGKILL, 3)]:
        if not group_actors(process.pid):
            break
        result['forced'] = True
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
        end = time.monotonic() + seconds
        while group_actors(process.pid) and time.monotonic() < end:
            time.sleep(.05)
    result['remaining'] = group_actors(process.pid)
    result['exitCode'] = process.wait(timeout=2)
    return result


def cleanup_containers(folder, cache, docker):
    """専用traceに記録された名前と実mount/commandが一致する対象だけを回収する。"""
    rows = []
    for path in sorted((folder / '0').glob('docker-*.json')):
        row = {'trace': path.name}
        rows.append(row)
        try:
            trace = json.loads(path.read_text())
            args = trace['arguments']
            if 'run' not in args or '--name' not in args:
                continue
            name = args[args.index('--name') + 1]
            row['name'] = name
            prefix, suffix = name.rsplit('-', 5)[0], '-'.join(name.split('-')[-5:])
            if str(uuid.UUID(suffix)) != suffix or not prefix.startswith('mcicon-'):
                raise ValueError('所有nameの形式が不明')
            inspected = subprocess.run([*docker, 'inspect', name], capture_output=True, text=True, timeout=15)
            if inspected.returncode == 1 and 'no such' in inspected.stderr.lower():
                row['absent'] = True
                continue
            if inspected.returncode:
                raise ValueError('所有containerのinspect失敗')
            info = json.loads(inspected.stdout)[0]
            if info['Image'] != IMAGE or info['Name'].lstrip('/') != name or info['HostConfig']['NetworkMode'] != 'none':
                raise ValueError('所有image/name/network不一致')
            mounts = {m['Destination']: m for m in info['Mounts']}
            if 'runDirectory' in trace:
                run = Path(trace['runDirectory']).resolve()
                if run.parent != cache or not run.name.startswith('worker-'):
                    raise ValueError('専用snapshot不一致')
                expected = {'/mcicon/cache': (str(cache), True), '/mcicon/worker.jar': (str(run / 'worker-agent.jar'), False)}
                actual = {key: (m['Source'], m['RW']) for key, m in mounts.items()}
                if actual != expected:
                    raise ValueError('専用mount不一致')
            elif mounts or args[-1] != '-version' or '--entrypoint' not in args:
                raise ValueError('Java診断の所有条件不一致')
            else:
                java = args[args.index('--entrypoint') + 1]
                if java != '/opt/java/java21/bin/java' or info['Config']['Entrypoint'] != [java] or info['Config']['Cmd'] != ['-version']:
                    raise ValueError('Java診断command不一致')
            row['unexpectedRemaining'] = True
            subprocess.run([*docker, 'rm', '--force', name], check=True, capture_output=True, timeout=15)
            checked = subprocess.run([*docker, 'inspect', name], capture_output=True, text=True, timeout=15)
            row['removed'] = checked.returncode == 1 and 'no such' in checked.stderr.lower()
            if not row['removed']:
                raise ValueError('所有container回収失敗')
        except Exception as error:
            row['failure'] = repr(error)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--hours', type=float, default=7)
    args = parser.parse_args()
    if not 0 < args.hours <= 7:
        parser.error('--hours は0より大きく7以下')
    bundle = args.bundle.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    freeze = json.loads((bundle / 'input-freeze.json').read_text())
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    docker = [shutil.which('docker'), '--context', 'colima-mcicon']
    lock = threading.Lock()
    started = time.monotonic()
    end = started + args.hours * 3600
    report = {'passed': False, 'complete': False, 'hours': args.hours, 'lanes': 2,
              'startedWallNs': time.time_ns(), 'jobs': [], 'failures': [],
              'inputFreezeSha256': hashlib.sha256((bundle / 'input-freeze.json').read_bytes()).hexdigest(),
              'harnessSourcesSha256': {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__).resolve(), REPO / 'scripts/verify-startup-diagnostics.py', REPO / 'scripts/qa/trace-docker.py']},
              'historicalCauseResolved': False, 'scope': '旧shield失敗世代・専用cache2個。元7時間全ID系列そのものではない。'}

    def save():
        target = out / 'report.json'
        temporary = out / 'report.json.part'
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(target)

    def lane(name):
        cache = bundle / ('cache-' + name)
        index = 0
        try:
            while not stop.is_set() and not (out / 'stop').exists() and time.monotonic() < end:
                if shutil.disk_usage(out).free < 15 * 1024**3:
                    raise RuntimeError('空き容量15GiB未満・新規起動停止')
                folder = out / name / str(index)
                folder.parent.mkdir(exist_ok=True)
                row = {'lane': name, 'index': index, 'startedWallNs': time.time_ns(), 'passed': False}
                command = [sys.executable, str(REPO / 'scripts/verify-startup-diagnostics.py'), '--cache', str(cache),
                           '--out', str(folder), '--cli', str(bundle / 'distribution/bin/mcicon'), '--version', '1.21.4',
                           '--id', 'shield', '--runs', '1', '--warm-cache', '--delayed-observation',
                           '--compare', str(bundle / 'reference/manifest.json')]
                # コピー元の初期化済みcacheはworker directoryを含めないため、2回目以降だけwarm判定となる。
                if not list(cache.glob('worker-*')):
                    command.remove('--warm-cache')
                with (folder.parent / (str(index) + '.stdout')).open('wb') as stdout, (folder.parent / (str(index) + '.stderr')).open('wb') as stderr:
                    process = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True)
                    row['diagnosticPid'] = process.pid
                    try:
                        wait_unreaped(process, 420, stop, out / 'stop')
                    except Exception as error:
                        row['failure'] = str(error)
                    finally:
                        try:
                            row['processGroup'] = reap_group(process)
                            row['exitCode'] = row['processGroup']['exitCode']
                        except Exception as error:
                            row['processGroupFailure'] = repr(error)
                            if process.returncode is None:
                                try:
                                    os.killpg(process.pid, signal.SIGKILL)
                                except ProcessLookupError:
                                    pass
                                except Exception as fallback:
                                    row['processGroupFallbackFailure'] = repr(fallback)
                                finally:
                                    try:
                                        row['fallbackRootExit'] = process.wait(timeout=5)
                                    except Exception as fallback:
                                        row['fallbackWaitFailure'] = repr(fallback)
                        try:
                            row['containers'] = cleanup_containers(folder, cache, docker)
                        except Exception as error:
                            row['containerCleanupFailure'] = repr(error)
                source = folder / 'report.json'
                try:
                    if source.exists():
                        detail = json.loads(source.read_text())
                        row['sourceReportSha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
                        row['gamePassed'] = len(detail['jobs']) == 1 and detail['jobs'][0]['passed']
                        row['coreSha256'] = detail['coreSha256']
                        row['workerSha256'] = detail['workerSha256']
                        row['identityMatched'] = (detail['coreSha256'] == 'eec62d03a0f004e4d3d4963eba31b399ace8b030135651c1480f4c40a92db825' and detail['workerSha256'] == '8b85e11a940ef3497937e0af1d9f3571e2ba2842a6cd2b11dafba027805acb42' and detail['sourceSha256'] == 'c17c450c6e72cc51297daa57ce38f800aa01cf022b743daa21a0512d326d894e' and detail['imageId'] == IMAGE)
                except Exception as error:
                    row['reportFailure'] = repr(error)
                row['passed'] = (row.get('exitCode') == 0 and row.get('gamePassed', False) and row.get('identityMatched', False) and
                                 not any(key in row for key in ['failure', 'processGroupFailure', 'containerCleanupFailure', 'reportFailure']) and
                                 not row.get('processGroup', {}).get('forced', True) and not row.get('processGroup', {}).get('remaining', True) and
                                 not any(r.get('failure') or r.get('unexpectedRemaining') for r in row.get('containers', [])))
                with lock:
                    row['finishedWallNs'] = time.time_ns()
                    report['jobs'].append(row)
                    save()
                    print(name, index, 'PASS' if row['passed'] else 'FAIL', flush=True)
                if not row['passed']:
                    raise RuntimeError('起動/観測/回収FAIL・自動retryなし')
                index += 1
        except Exception as error:
            stop.set()
            with lock:
                report['failures'].append({'lane': name, 'failure': repr(error)})
                save()

    try:
        for name, expected in freeze['distributionFilesSha256'].items():
            if hashlib.sha256((bundle / 'distribution' / name).read_bytes()).hexdigest() != expected:
                raise ValueError('配布snapshotのhash不一致')
        report['distributionFilesSha256'] = freeze['distributionFilesSha256']
        for name, expected in freeze['referenceFiles'].items():
            if hashlib.sha256((bundle / name).read_bytes()).hexdigest() != expected:
                raise ValueError('固定参照のhash不一致')
        report['referenceFiles'] = freeze['referenceFiles']
        for name in ['a', 'b']:
            for item in freeze['files']:
                if hashlib.sha256((bundle / ('cache-' + name) / item['path']).read_bytes()).hexdigest() != item['sha256']:
                    raise ValueError('専用入力のhash不一致')
        for path, expected in [('lib/core-0.1.0-SNAPSHOT.jar', 'eec62d03a0f004e4d3d4963eba31b399ace8b030135651c1480f4c40a92db825'),
                               ('lib/cli-0.1.0-SNAPSHOT.jar', '008a6f0094502089161c07266ef16388670473694a18df5bffe399df0f2b24bc'),
                               ('worker/worker-agent.jar', '8b85e11a940ef3497937e0af1d9f3571e2ba2842a6cd2b11dafba027805acb42')]:
            if hashlib.sha256((bundle / 'distribution' / path).read_bytes()).hexdigest() != expected:
                raise ValueError('旧失敗世代のJAR不一致')
        image = subprocess.check_output([*docker, 'image', 'inspect', IMAGE, '--format', '{{.Id}}'], text=True, timeout=15).strip()
        if image != IMAGE:
            raise ValueError('固定image不一致')
        threads = [threading.Thread(target=lane, args=(name,)) for name in ['a', 'b']]
        save()
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        report['changedInputs'] = {name: [item['path'] for item in freeze['files'] if hashlib.sha256((bundle / ('cache-' + name) / item['path']).read_bytes()).hexdigest() != item['sha256']] for name in ['a', 'b']}
        report['changedReferences'] = [name for name, expected in freeze['referenceFiles'].items() if hashlib.sha256((bundle / name).read_bytes()).hexdigest() != expected]
        report['changedDistributionFiles'] = [name for name, expected in freeze['distributionFilesSha256'].items() if hashlib.sha256((bundle / 'distribution' / name).read_bytes()).hexdigest() != expected]
        report['complete'] = time.monotonic() >= end and not stop.is_set() and not (out / 'stop').exists()
        report['passed'] = report['complete'] and not report['failures'] and bool(report['jobs']) and all(r['passed'] for r in report['jobs']) and not any(report['changedInputs'].values()) and not report['changedReferences'] and not report['changedDistributionFiles']
    except Exception as error:
        report['failures'].append({'failure': repr(error)})
    finally:
        report['elapsedSeconds'] = round(time.monotonic() - started, 3)
        report['finishedWallNs'] = time.time_ns()
        save()
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
