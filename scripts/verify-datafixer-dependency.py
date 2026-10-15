#!/usr/bin/env python3
"""最適化taskの保留中にMainがjoinへ到達するかを、旧workerで制御する。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading
import time

REPO = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def observe_gate(folder, cache, docker, done, result):
    run = None
    try:
        deadline = time.monotonic() + 60
        waiting = None
        while not done.is_set() and time.monotonic() < deadline:
            trace = folder / '0/docker-run.json'
            if trace.exists():
                try:
                    life = json.loads(trace.read_text())
                except ValueError:
                    done.wait(.05)
                    continue
                run = Path(life['runDirectory']).resolve()
                if run.parent != cache or not run.name.startswith('worker-'):
                    raise ValueError('専用run directoryではない')
                stages = (run / 'startup-stages.tsv').read_text() if (run / 'startup-stages.tsv').exists() else ''
                gate = (run / 'datafixer-gate.tsv').read_text() if (run / 'datafixer-gate.tsv').exists() else ''
                if 'gate.waiting\t' in gate:
                    waiting = waiting or time.monotonic()
                    reached = 'join.before\t' in stages
                    if reached or time.monotonic() - waiting >= 15:
                        result['joinObservedAtDumpRequest'] = reached
                        result['stagesAtDumpRequest'] = stages
                        result['gateAtDumpRequest'] = gate
                        if 'gate.released\t' in gate or 'join.after\t' in stages:
                            raise ValueError('解除前の保留を観測できなかった')
                        inspected = subprocess.run([*docker, 'inspect', life['containerName']], capture_output=True, text=True, check=True, timeout=10)
                        info = json.loads(inspected.stdout)[0]
                        mounts = {m['Destination']: (m['Source'], m['RW']) for m in info['Mounts']}
                        if info['Image'] != 'sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf' or info['Name'].lstrip('/') != life['containerName'] or mounts != {'/mcicon/cache': (str(cache), True), '/mcicon/worker.jar': (str(run / 'worker-agent.jar'), False)} or info['HostConfig']['NetworkMode'] != 'none':
                            raise ValueError('所有containerの条件不一致')
                        result['containerId'] = info['Id']
                        with (folder / 'gate-held-threads.log').open('wb') as output:
                            dump = subprocess.run([*docker, 'exec', info['Id'], '/opt/java/java21/bin/jcmd', '1', 'Thread.print'], stdout=output, stderr=subprocess.STDOUT, timeout=15)
                        result['threadDumpExitCode'] = dump.returncode
                        result['dumpFinishedHostMonotonicNs'] = time.monotonic_ns()
                        stages = (run / 'startup-stages.tsv').read_text()
                        gate = (run / 'datafixer-gate.tsv').read_text()
                        result['stagesBeforeRelease'] = stages
                        result['gateBeforeRelease'] = gate
                        result['joinObservedInLastHeldSnapshot'] = 'join.before\t' in stages
                        if 'gate.released\t' in gate or 'gate.expired\t' in gate or 'join.after\t' in stages:
                            raise ValueError('dump後の保留を観測できなかった')
                        # 結果に関わらず解除し、元workerによる終了と所有回収を待つ。
                        return
            done.wait(.05)
        result['failure'] = 'gateを観測する前に終了または期限へ到達した'
    except Exception as error:
        result['failure'] = repr(error)
    finally:
        if run is not None and run.parent == cache and run.name.startswith('worker-'):
            try:
                (run / 'datafixer-release').write_text('release\n')
                result['releaseHostMonotonicNs'] = time.monotonic_ns()
            except Exception as error:
                result['releaseFailure'] = repr(error)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True, help='cache/distribution/reference/input-freeze.jsonを持つ専用snapshot')
    p.add_argument('--stage-agent', type=Path, required=True)
    p.add_argument('--gate-agent', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    bundle = a.bundle.resolve()
    cache = bundle / 'cache'
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    stages = load('stages', REPO / 'scripts/verify-startup-stages.py')
    soak = load('soak', REPO / 'scripts/verify-startup-soak.py')
    docker = [shutil.which('docker'), '--context', 'colima-mcicon']
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    freeze = json.loads((bundle / 'input-freeze.json').read_text())
    fixed = {cache / row['path']: row['sha256'] for row in freeze['files']}
    fixed.update({bundle / path: digest for path, digest in freeze['referenceFiles'].items()})
    fixed.update({bundle / 'distribution' / path: digest for path, digest in freeze['distributionFilesSha256'].items()})
    sources = [Path(__file__), REPO / 'scripts/verify-startup-diagnostics.py', REPO / 'scripts/qa/trace-docker.py', REPO / 'scripts/verify-startup-stages.py', REPO / 'scripts/verify-startup-soak.py']
    source_pins = {str(path): sha(path) for path in sources}
    report = {'passed': False, 'complete': False, 'jobs': [], 'historicalCauseResolved': False,
              'stageAgentSha256': sha(a.stage_agent), 'gateAgentSha256': sha(a.gate_agent),
              'harnessSourcesSha256': source_pins, 'scope': '単一JVMずつのcontrol/gated制御。元timeoutの再現試験とは区別する。'}

    def save():
        (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    try:
        if any(sha(path) != digest for path, digest in fixed.items()): raise ValueError('事前入力hash不一致')
        for condition in ['control', 'gated']:
            if stop.is_set() or (out / 'stop').exists(): break
            folder = out / condition
            row = {'condition': condition, 'passed': False}
            report['jobs'].append(row)
            command = [sys.executable, str(REPO / 'scripts/verify-startup-diagnostics.py'), '--cache', str(cache), '--out', str(folder),
                       '--cli', str(bundle / 'distribution/bin/mcicon'), '--version', '1.21.4', '--id', 'shield', '--runs', '1', '--delayed-observation',
                       '--compare', str(bundle / 'reference/manifest.json'), '--stage-agent', str(a.stage_agent.resolve())]
            if list(cache.glob('worker-*')): command.append('--warm-cache')
            if condition == 'gated': command += ['--datafixer-gate-agent', str(a.gate_agent.resolve())]
            with (out / (condition + '.stdout')).open('wb') as stdout, (out / (condition + '.stderr')).open('wb') as stderr:
                process = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True)
                done = threading.Event()
                observation = {}
                observer = threading.Thread(target=observe_gate, args=(folder, cache, docker, done, observation)) if condition == 'gated' else None
                if observer: observer.start()
                try:
                    soak.wait_unreaped(process, 420, stop, out / 'stop')
                except Exception as error:
                    row['failure'] = repr(error)
                finally:
                    done.set()
                    if observer:
                        observer.join(30)
                        if observer.is_alive(): row['observerFailure'] = '解除監督が終了期限を超過した'
                    try:
                        row['processGroup'] = soak.reap_group(process)
                    except Exception as error:
                        row['processGroupFailure'] = repr(error)
                        if process.returncode is None:
                            try: os.killpg(process.pid, signal.SIGKILL)
                            except ProcessLookupError: pass
                            finally: row['fallbackExitCode'] = process.wait(timeout=5)
                    finally:
                        try:
                            row['containers'] = soak.cleanup_containers(folder, cache, docker)
                        except Exception as error:
                            row['containerCleanupFailure'] = repr(error)
                        if observer:
                            row['gateObservation'] = observation
                            save()
                            trace = folder / '0/docker-run.json'
                            if trace.exists():
                                run = Path(json.loads(trace.read_text())['runDirectory']).resolve()
                                if run.parent == cache and run.name.startswith('worker-') and (run / 'datafixer-gate.tsv').exists():
                                    shutil.copyfile(run / 'datafixer-gate.tsv', folder / 'datafixer-gate.tsv')
                        save()
            detail = json.loads((folder / 'report.json').read_text())
            row['sourceReportSha256'] = sha(folder / 'report.json')
            game = detail['jobs'][0]
            if not game['passed']: raise ValueError('旧worker描画FAIL。source reportとraw証拠を参照')
            life = game['lifecycle']
            run = Path(life['runDirectory'])
            if detail['coreSha256'] != 'eec62d03a0f004e4d3d4963eba31b399ace8b030135651c1480f4c40a92db825' or detail['workerSha256'] != '8b85e11a940ef3497937e0af1d9f3571e2ba2842a6cd2b11dafba027805acb42' or detail['sourceSha256'] != 'c17c450c6e72cc51297daa57ce38f800aa01cf022b743daa21a0512d326d894e' or detail['imageId'] != soak.IMAGE: raise ValueError('旧失敗世代ではない')
            row['stageObservation'] = stages.read_stages(folder / '0/startup-stages.tsv')
            manifest = folder / '0/output/java/1.21.4/icons/manifest.json'
            m = json.loads(manifest.read_text())
            reference = json.loads((bundle / 'reference/manifest.json').read_text())
            row['metadataFieldsCompared'] = stages.compare_metadata(m, reference)
            entry = next(e for e in reference['entries'] if e['id'] == 'minecraft:shield')
            if (manifest.parent / entry['path']).read_bytes() != (bundle / 'reference' / entry['path']).read_bytes(): raise ValueError('旧PNG不一致')
            row['pngBytesEqual'] = True
            if condition == 'gated':
                gate_lines = [line.split('\t') for line in (folder / 'datafixer-gate.tsv').read_text().splitlines()]
                labels = [line[0] for line in gate_lines]
                if labels != ['agent.ready', 'gate.waiting', 'gate.released', 'gate.first-task.finished'] or observation.get('failure') or observation.get('releaseFailure') or observation.get('threadDumpExitCode') != 0: raise ValueError('保留/解除/dumpの制御不一致')
                if life['gateAgentSha256'] != report['gateAgentSha256']: raise ValueError('gate agent不一致')
                effective = list(life['arguments'])
                extra = [v for v in effective if '/datafixer-gate.jar=' in v]
                if len(extra) != 1: raise ValueError('gate引数が一意でない')
                effective.remove(extra[0])
                if effective != life['argumentsBeforeGate']: raise ValueError('gate以外の引数を変更した')
                dump = (folder / 'gate-held-threads.log').read_text()
                blocks = dump.split('\n\n')
                row['mainAtJoinInDump'] = any('"main"' in block and 'CompletableFuture.join' in block and 'net.minecraft.client.main.Main.main' in block for block in blocks)
                row['datafixerHeldInDump'] = any('"Datafixer Bootstrap"' in block and 'DatafixerGateAgent' in block for block in blocks)
                if not row['datafixerHeldInDump']: raise ValueError('dumpにDatafixer保留がない')
                if observation['joinObservedAtDumpRequest'] and not row['mainAtJoinInDump']: raise ValueError('到達境界とdumpの待ち先が対応しない')
                join_time = next(int(line.split('\t')[1]) for line in (folder / '0/startup-stages.tsv').read_text().splitlines() if line.startswith('join.before\t'))
                release_time = next(int(line[1]) for line in gate_lines if line[0] == 'gate.released')
                row['joinBeforeGateRelease'] = join_time < release_time
            elif 'gateAgentSha256' in life or (run / 'datafixer-gate.tsv').exists():
                raise ValueError('controlへgateが混入した')
            if life['stageAgentSha256'] != report['stageAgentSha256']: raise ValueError('stage agent不一致')
            row['passed'] = (row.get('processGroup') == {'forced': False, 'remaining': [], 'exitCode': 0} and not any(row.get(k) for k in ['failure', 'observerFailure', 'processGroupFailure', 'containerCleanupFailure']) and not any(r.get('failure') or r.get('unexpectedRemaining') for r in row.get('containers', [])))
            save()
            print(condition, 'PASS' if row['passed'] else 'FAIL', flush=True)
            if not row['passed']: break
        report['changedInputs'] = [str(path) for path, digest in fixed.items() if sha(path) != digest]
        report['changedSources'] = [path for path, digest in source_pins.items() if sha(Path(path)) != digest]
        report['complete'] = len(report['jobs']) == 2
        report['passed'] = report['complete'] and all(r['passed'] for r in report['jobs']) and not report['changedInputs'] and not report['changedSources']
    except Exception as error:
        report['failure'] = repr(error)
    finally:
        save()
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
