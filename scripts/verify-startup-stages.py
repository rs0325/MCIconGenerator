#!/usr/bin/env python3
"""旧workerの境界記録あり/なしをABBA順・各2並列で比較する診断QA。"""
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
STAGES = ['agent.ready', 'main.entry', 'hkl.enter', 'hkl.exit', 'main.telemetry.ready',
          'bootstrap.before', 'bootstrap.after', 'join.before', 'join.after', 'client.before', 'client.after']
METADATA = ['schemaVersion', 'toolVersion', 'adapterVersion', 'edition', 'version', 'mode', 'sourceSha256',
            'settings', 'settingsDigest', 'context', 'catalog', 'environment', 'renderer', 'javaMajor']


def compare_metadata(manifest, reference):
    changed = [key for key in METADATA if key not in manifest or key not in reference or manifest[key] != reference[key]]
    if changed: raise ValueError('固定metadata不一致: ' + ','.join(changed))
    return len(METADATA)


def read_stages(path):
    rows = [line.split('\t') for line in path.read_text().splitlines()]
    if [r[0] for r in rows] != STAGES or any(len(r) != 3 for r in rows):
        raise ValueError('起動境界の欠落/重複/順序違反')
    times = [int(r[1]) for r in rows]
    if times != sorted(times) or len({r[2] for r in rows[1:]}) != 1:
        raise ValueError('単調時刻またはmain threadが不一致')
    return {'stages': [{'name': r[0], 'elapsedMillis': (int(r[1]) - times[0]) / 1e6, 'threadId': int(r[2])} for r in rows],
            'clientMillis': (times[-1] - times[-2]) / 1e6,
            'bootstrapMillis': (times[6] - times[5]) / 1e6}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True, help='input-freeze.json付き旧失敗世代の専用snapshot')
    p.add_argument('--agent', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    bundle = a.bundle.resolve()
    agent = a.agent.resolve(strict=True)
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('soak', REPO / 'scripts/verify-startup-soak.py')
    soak = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(soak)
    docker = [shutil.which('docker'), '--context', 'colima-mcicon']
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    freeze = json.loads((bundle / 'input-freeze.json').read_text())
    report = {'passed': False, 'complete': False, 'jobs': [], 'failures': [],
              'historicalCauseResolved': False, 'order': ['control', 'stage', 'stage', 'control'],
              'agentSha256': hashlib.sha256(agent.read_bytes()).hexdigest(),
              'scope': '旧shield世代の専用cache2個。追加agentありは観測条件。8起動から性能差や原因解消は断定しない。',
              'inputFreezeSha256': hashlib.sha256((bundle / 'input-freeze.json').read_bytes()).hexdigest(),
              'sourcesSha256': {str(f.relative_to(REPO)): hashlib.sha256(f.read_bytes()).hexdigest() for f in [Path(__file__).resolve(), REPO / 'scripts/verify-startup-diagnostics.py', REPO / 'scripts/verify-startup-soak.py', REPO / 'scripts/qa/trace-docker.py']}}

    def save():
        (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    def unchanged():
        changed = []
        for lane in ['a', 'b']:
            changed += [lane + '/' + r['path'] for r in freeze['files'] if hashlib.sha256((bundle / ('cache-' + lane) / r['path']).read_bytes()).hexdigest() != r['sha256']]
        changed += [name for name, digest in freeze['referenceFiles'].items() if hashlib.sha256((bundle / name).read_bytes()).hexdigest() != digest]
        changed += ['distribution/' + name for name, digest in freeze['distributionFilesSha256'].items() if hashlib.sha256((bundle / 'distribution' / name).read_bytes()).hexdigest() != digest]
        return changed

    def job(batch, lane, condition):
        cache = bundle / ('cache-' + lane)
        folder = out / str(batch) / lane
        folder.parent.mkdir(exist_ok=True)
        row = {'batch': batch, 'lane': lane, 'condition': condition, 'passed': False, 'startedWallNs': time.time_ns()}
        command = [sys.executable, str(REPO / 'scripts/verify-startup-diagnostics.py'), '--cache', str(cache),
                   '--out', str(folder), '--cli', str(bundle / 'distribution/bin/mcicon'), '--version', '1.21.4',
                   '--id', 'shield', '--runs', '1', '--delayed-observation', '--compare', str(bundle / 'reference/manifest.json')]
        if list(cache.glob('worker-*')): command.append('--warm-cache')
        if condition == 'stage': command += ['--stage-agent', str(agent)]
        try:
            with (folder.parent / (lane + '.stdout')).open('wb') as stdout, (folder.parent / (lane + '.stderr')).open('wb') as stderr:
                process = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True)
                row['diagnosticPid'] = process.pid
                try:
                    soak.wait_unreaped(process, 420, stop, out / 'stop')
                except Exception as error:
                    row['failure'] = repr(error)
                finally:
                    try:
                        row['processGroup'] = soak.reap_group(process)
                    except Exception as error:
                        row['processGroupFailure'] = repr(error)
                        if process.returncode is None:
                            try:
                                os.killpg(process.pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                            except Exception as fallback:
                                row['fallbackSignalFailure'] = repr(fallback)
                            finally:
                                row['fallbackExitCode'] = process.wait(timeout=5)
                    finally:
                        row['containers'] = soak.cleanup_containers(folder, cache, docker)
            detail = json.loads((folder / 'report.json').read_text())
            row['sourceReportSha256'] = hashlib.sha256((folder / 'report.json').read_bytes()).hexdigest()
            game = detail['jobs'][0]
            trace = game['lifecycle']
            if not game['passed']: raise ValueError('描画診断FAIL。source reportとraw証拠を参照')
            manifest = json.loads((folder / '0/output/java/1.21.4/icons/manifest.json').read_text())
            reference = json.loads((bundle / 'reference/manifest.json').read_text())
            row['metadataFieldsCompared'] = compare_metadata(manifest, reference)
            entry = next(entry for entry in reference['entries'] if entry['id'] == 'minecraft:shield')
            png = folder / '0/output/java/1.21.4/icons' / entry['path']
            reference_png = bundle / 'reference' / entry['path']
            if png.read_bytes() != reference_png.read_bytes(): raise ValueError('固定PNG bytes不一致')
            row['pngBytesEqual'] = True
            row['readySeconds'] = (trace['readyMonotonicNs'] - trace['startedMonotonicNs']) / 1e9
            row['identityMatched'] = (detail['coreSha256'] == 'eec62d03a0f004e4d3d4963eba31b399ace8b030135651c1480f4c40a92db825' and detail['workerSha256'] == '8b85e11a940ef3497937e0af1d9f3571e2ba2842a6cd2b11dafba027805acb42' and detail['sourceSha256'] == 'c17c450c6e72cc51297daa57ce38f800aa01cf022b743daa21a0512d326d894e' and detail['imageId'] == soak.IMAGE)
            if condition == 'stage':
                row['stageObservation'] = read_stages(folder / '0/startup-stages.tsv')
                if trace['stageAgentSha256'] != report['agentSha256']: raise ValueError('追加agent不一致')
                effective = list(trace['arguments'])
                extra = [v for v in effective if v.startswith('-javaagent:/mcicon/cache/')]
                if len(extra) != 1: raise ValueError('追加agent引数が一意でない')
                effective.remove(extra[0])
                if effective != trace['originalArguments']: raise ValueError('旧起動引数を変更した')
            elif (folder / '0/startup-stages.tsv').exists() or 'stageAgentSha256' in trace:
                raise ValueError('対照へagentが混入した')
            row['passed'] = (len(detail['jobs']) == 1 and game['passed'] and row['identityMatched'] and
                             row.get('processGroup', {}).get('exitCode') == 0 and not row['processGroup']['forced'] and not row['processGroup']['remaining'] and
                             'failure' not in row and 'processGroupFailure' not in row and not any(r.get('failure') or r.get('unexpectedRemaining') for r in row['containers']))
        except Exception as error:
            row['failure'] = repr(error)
        if not row['passed']: stop.set()
        row['finishedWallNs'] = time.time_ns()
        return row

    try:
        if unchanged(): raise ValueError('事前入力hash不一致')
        save()
        for batch, condition in enumerate(report['order']):
            if stop.is_set() or (out / 'stop').exists(): break
            results = {}
            threads = [threading.Thread(target=lambda lane=lane: results.update({lane: job(batch, lane, condition)})) for lane in ['a', 'b']]
            for thread in threads: thread.start()
            for thread in threads: thread.join()
            report['jobs'].extend(results[lane] for lane in ['a', 'b'])
            save()
            print(batch, condition, 'PASS' if all(r['passed'] for r in results.values()) else 'FAIL', flush=True)
        report['changedInputs'] = unchanged()
        report['complete'] = len(report['jobs']) == 8
        report['passed'] = report['complete'] and not stop.is_set() and not report['changedInputs'] and all(r['passed'] for r in report['jobs'])
    except Exception as error:
        report['failures'].append(repr(error))
    finally:
        report['finishedWallNs'] = time.time_ns()
        save()
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
