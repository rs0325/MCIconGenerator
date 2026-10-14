#!/usr/bin/env python3
"""独立JVMの並行準備を実行し、例外とcacheの確定状態を保存する。"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import platform
import shutil
import subprocess
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def snapshot(cache):
    hashes, parts = {}, []
    for path in sorted(cache.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(cache).as_posix()
        if path.name.endswith('.part'):
            parts.append({'path': relative, 'bytes': path.stat().st_size})
        elif not path.name.endswith('.lock'):
            with path.open('rb') as stream:
                hashes[relative] = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'files': hashes, 'parts': parts}


def evaluate(result, baseline, before, after):
    return (result['exitCode'] == 0 and result['prepared'] == baseline
            and before['files'] == after['files'] and not after['parts'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=REPO / '.local/cache')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--version', default='1.13')
    parser.add_argument('--online', action='store_true', help='公式HTTPSから更新・不足入力を取得する')
    parser.add_argument('--cli', type=Path, default=REPO / 'cli/build/install/mcicon/bin/mcicon')
    parser.add_argument('--rounds', type=int, choices=range(1, 11), default=4)
    parser.add_argument('--concurrency', type=int, choices=range(1, 5), default=2)
    parser.add_argument('--timeout', type=int, choices=range(1, 601), default=120)
    args = parser.parse_args()
    cache, out = args.cache.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    distribution = out / 'mcicon'
    shutil.copytree(args.cli.resolve().parent.parent, distribution)
    jars = sorted((distribution / 'lib').glob('*.jar'))
    classpath = os.pathsep.join(map(str, jars))
    classes = out / 'classes'
    classes.mkdir()
    probe = REPO / 'scripts/qa/RuntimePrepareProbe.java'
    subprocess.run(['javac', '--release', '21', '-cp', classpath, '-d', str(classes),
                    str(probe)], check=True)
    command = ['java', '-cp', str(classes) + os.pathsep + classpath,
               'io.github.rs0325.mcicon.qa.RuntimePrepareProbe', str(cache), args.version, str(not args.online).lower()]
    report = {
        'version': args.version, 'concurrency': args.concurrency, 'rounds': args.rounds,
        'timeoutSeconds': args.timeout, 'offline': not args.online, 'gameWorkersStarted': 0,
        'previousFailureRootCauseEstablished': False,
        'os': platform.platform(), 'java': subprocess.run(
            ['java', '-version'], capture_output=True, text=True, check=True).stderr,
        'cache': str(cache), 'command': command,
        'binarySha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars},
        'probeSha256': hashlib.sha256(probe.read_bytes()).hexdigest(), 'results': [],
    }

    def save():
        temporary = out / 'report.json.part'
        temporary.write_text(json.dumps(report, indent=2) + '\n')
        temporary.replace(out / 'report.json')

    def run(name):
        started = time.time()
        with (out / (name + '.json')).open('w') as stdout, (out / (name + '.log')).open('w') as stderr:
            try:
                code = subprocess.run(command, stdout=stdout, stderr=stderr,
                                      timeout=args.timeout).returncode
            except subprocess.TimeoutExpired:
                code = None
        prepared, parse_error = None, None
        if code == 0:
            try:
                prepared = json.loads((out / (name + '.json')).read_text())
            except (ValueError, OSError) as error:
                parse_error = str(error)
        return {'run': name, 'startedUnixSeconds': started,
                'elapsedSeconds': time.time() - started, 'exitCode': code,
                'timedOut': code is None, 'parseError': parse_error, 'prepared': prepared}

    report['beforeBaseline'] = snapshot(cache)
    save()
    baseline = run('baseline')
    report['baselineRun'] = baseline
    report['baselineCache'] = snapshot(cache)
    save()
    if baseline['exitCode'] != 0 or baseline['prepared'] is None or report['baselineCache']['parts']:
        print('基準準備失敗: baseline.log / report.jsonを確認してください。', flush=True)
        return 1
    for round_number in range(1, args.rounds + 1):
        before = snapshot(cache)
        names = [f'round-{round_number}-{i + 1}' for i in range(args.concurrency)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            results = list(pool.map(run, names))
        after = snapshot(cache)
        for result in results:
            result['inputAndDerivedBytesMatch'] = evaluate(result, baseline['prepared'], report['baselineCache'], after)
        report['results'].append({'round': round_number, 'before': before, 'after': after,
                                  'processes': results})
        save()
        passed = all(result['inputAndDerivedBytesMatch'] for result in results)
        print('round', round_number, 'PASS' if passed else 'FAIL', flush=True)
        if not passed:
            return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
