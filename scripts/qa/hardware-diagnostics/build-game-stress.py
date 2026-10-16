#!/usr/bin/env python3
"""固定1.21.4の実OSHI反復QAを作り、両workerのMain変換境界を確認する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--old-worker', type=Path, required=True)
    parser.add_argument('--fixed-worker', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    stage = source.parent / 'startup-stage'
    cache = args.cache.resolve()
    old, fixed = args.old_worker.resolve(), args.fixed_worker.resolve()
    asm = cache / 'libraries/org/ow2/asm/asm/9.6/asm-9.6.jar'
    client = cache / 'versions/1.21.4/client.jar'
    mappings = cache / 'versions/1.21.4/mappings.txt'
    expected = [
        (old, 'edc897bca0a2accb85f540ab41c8ad4c9040d514ae0a44a2f273fcd3a607031c'),
        (fixed, '2e66c86e756439a81c972232bbfe8fe87c47c6fc63820f4cd6d658e0819141ae'),
        (client, 'c17c450c6e72cc51297daa57ce38f800aa01cf022b743daa21a0512d326d894e'),
        (mappings, '48b502ccc5e855b49da8aa9c0c0d7c565bec54c9f5da3a3664528aff7b9fdf23'),
        (asm, '3c6fac2424db3d4a853b669f4e3d1d9c3c552235e19a319673f887083c2303a1'),
    ]
    for path, digest in expected:
        if sha(path) != digest:
            raise ValueError(f'測定世代の入力と不一致: {path}')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    subprocess.run([sys.executable, str(source / 'build.py'), '--worker', str(fixed),
                    '--out', str(out / 'guard')], check=True, timeout=100)
    guard = out / 'guard/spawn-guard.jar'
    classes = out / 'classes'
    sources = [source / 'GameOshiStressAgent.java', source / 'StressTransformCheck.java',
               stage / 'StartupStageAgent.java', stage / 'StartupStageCheck.java']
    cp = os.pathsep.join(map(str, [fixed, guard, asm]))
    subprocess.run([str(java / 'javac'), '--release', '21', '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(classes), *map(str, sources)], check=True, timeout=60)
    agents = {}
    for mode in ['old', 'fixed']:
        jar = out / (mode + '.jar')
        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
            target.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: mcicon.qa.GameOshiStressAgent\nCan-Retransform-Classes: true\n\n')
            target.writestr('qa-guard', str(mode == 'fixed').lower() + '\n')
            for path in sorted(classes.rglob('GameOshiStressAgent*.class')):
                target.write(path, path.relative_to(classes).as_posix())
            with zipfile.ZipFile(guard) as dependency:
                for name in dependency.namelist():
                    if name.endswith('.class') and (name.startswith('io/github/rs0325/mcicon/qa/SpawnGuard')
                            or name.startswith('io/github/rs0325/mcicon/worker/shaded/asm/')):
                        target.writestr(name, dependency.read(name))
        agents[mode] = {'path': str(jar), 'sha256': sha(jar)}
    checks = []
    for mode, worker in [('old', old), ('fixed', fixed)]:
        for reject in [False, True]:
            check_out = out / (mode + ('-reject' if reject else '-check'))
            check_out.mkdir()
            cp = os.pathsep.join(map(str, [classes, out / 'old.jar', worker, asm]))
            command = [str(java / 'java'), '-cp', cp, 'mcicon.qa.StressTransformCheck',
                       str(check_out / 'stages.tsv'), str(client), str(mappings)]
            if reject:
                command.append('reject')
            check = subprocess.run(command, capture_output=True, timeout=20)
            (check_out / 'stdout.log').write_bytes(check.stdout)
            (check_out / 'stderr.log').write_bytes(check.stderr)
            checks.append({'mode': mode, 'rejectMutatedInput': reject, 'exitCode': check.returncode,
                           'passed': check.returncode == (97 if reject else 0)})
    with zipfile.ZipFile(out / 'old.jar') as a, zipfile.ZipFile(out / 'fixed.jar') as b:
        classes_equal = all(a.read(name) == b.read(name) for name in a.namelist() if name.endswith('.class'))
    inputs = [old, fixed, asm, client, mappings, *sources, source / 'build.py',
              source / 'SpawnGuard.java', source / 'GuardCheck.java', Path(__file__)]
    report = {'passed': classes_equal and all(c['passed'] for c in checks),
              'scope': 'ホストの変換・拒否検査。ゲーム内guardの校正やnative停止試験とは区別する。',
              'classBytesEqualBothModes': classes_equal, 'agents': agents, 'checks': checks,
              'inputsSha256': {str(p): sha(p) for p in inputs}}
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('実OSHI反復QAの変換・拒否検査 ' + ('PASS' if report['passed'] else 'FAIL'))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
