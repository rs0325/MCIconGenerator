#!/usr/bin/env python3
"""個別CLIジョブを全登録アイテムで起動し、独立した全件ジョブと比較する。

公式入力・画像・workerログは指定したローカル出力先だけに保存する。
各個別ジョブは新しい出力先・新しいゲームプロセスを使う。
"""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import time

repo = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('audit_export', repo / 'scripts/audit-export.py')
audit_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_single(manifest, baseline, item):
    """対象が1件だけ成功し、入力・設定・RGBA・パスが全件と一致するか検査する。"""
    single = json.loads(manifest.read_text())
    full = json.loads(baseline.read_text())
    for key in ('sourceSha256', 'settings', 'settingsDigest', 'renderer', 'context', 'environment', 'catalog'):
        if single[key] != full[key]:
            raise ValueError(key + 'が全件ジョブと異なる')
    if not single['complete'] or single['counts']['failed'] or len(single['entries']) != 1:
        raise ValueError('個別ジョブが1件だけ成功していない')
    entry = single['entries'][0]
    if entry['id'] != item:
        raise ValueError('個別ジョブのIDが指定と異なる')
    report = audit_module.audit(manifest, compare=baseline)
    if report['failures'] or report['compared'] != 1 or not report['comparisonVerified']:
        raise ValueError(str(report))
    width, height, rgba = audit_module.rgba_png((manifest.parent / entry['path']).read_bytes())
    return {'id': item, 'path': entry['path'], 'pngSha256': entry['sha256'],
            'rgbaSha256': hashlib.sha256(rgba).hexdigest(), 'width': width, 'height': height,
            'manifestSha256': sha(manifest), 'comparisonVerified': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='1.21.4')
    parser.add_argument('--size', type=int, choices=(16, 32, 64, 128, 256, 512), default=64)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--workers', type=int, choices=(1, 2), default=1)
    parser.add_argument('--ids', nargs='+', help='調査用の部分集合。全ID受入の成功とは扱わない')
    parser.add_argument('--docker-context', default='colima-mcicon')
    parser.add_argument('--docker-image', default='mcicon-render:dev')
    args = parser.parse_args()
    out = args.out.resolve()
    if out.exists():
        parser.error('--outには新しいディレクトリを指定してください')
    out.mkdir(parents=True)
    distribution = out / 'cli'
    shutil.copytree(repo / 'cli/build/install/mcicon', distribution)
    cli = distribution / 'bin/mcicon'
    common = [str(cli), 'render', '--version', args.version, '--size', str(args.size),
              '--cache', str(args.cache.resolve()), '--offline', '--allow-experimental', '--json',
              '--docker-context', args.docker_context, '--docker-image', args.docker_image]

    def run(output, selection):
        output.mkdir()
        started = time.monotonic()
        with (output / 'result.json').open('w') as result, (output / 'cli.log').open('w') as log:
            process = subprocess.run([*common, '--out', str(output), *selection], stdout=result, stderr=log)
        return process.returncode, round(time.monotonic() - started, 3)

    started = time.monotonic()
    full_out = out / 'all'
    exit_code, elapsed = run(full_out, ['--all'])
    baseline = full_out / 'java' / args.version / 'icons/manifest.json'
    if exit_code:
        raise RuntimeError('全件ジョブ失敗: ' + str(full_out / 'cli.log'))
    audit = audit_module.audit(baseline, require_all=True)
    full = json.loads(baseline.read_text())
    if not full['complete'] or audit['failures'] or not audit['registryPopulationVerified']:
        raise RuntimeError(str(audit))
    population = [entry['id'] for entry in full['entries']]
    ids = sorted(set(args.ids)) if args.ids else population
    if not set(ids).issubset(population):
        parser.error('--idsは全件manifestにある完全なIDで指定してください')
    report = {'version': args.version, 'size': args.size, 'registryPopulation': len(population),
              'selected': len(ids), 'independentCliAndGameProcessPerId': True,
              'freshOutputPerJob': True, 'offline': True, 'concurrency': args.workers,
              'allJob': {'exitCode': exit_code, 'elapsedSeconds': elapsed, 'audit': audit,
                         'manifestSha256': sha(baseline)},
              'sourceSha256': full['sourceSha256'], 'settings': full['settings'],
              'context': full['context'], 'environment': full['environment'],
              'scriptSha256': sha(Path(__file__)), 'auditScriptSha256': sha(repo / 'scripts/audit-export.py'),
              'cliCoreSha256': sha(next((distribution / 'lib').glob('core-*.jar'))),
              'entries': [], 'allIdsVerified': False}
    single_root = out / 'single'
    single_root.mkdir()

    def individual(index_item):
        index, item = index_item
        output = single_root / str(index)
        code, seconds = run(output, ['--id', item])
        result = {'id': item, 'index': index, 'exitCode': code, 'elapsedSeconds': seconds,
                  'comparisonVerified': False}
        try:
            if code:
                raise ValueError('個別CLIが失敗: exit ' + str(code))
            result.update(verify_single(output / 'java' / args.version / 'icons/manifest.json', baseline, item))
        except Exception as error:
            result['error'] = str(error)
        return result

    def save():
        report['entries'].sort(key=lambda entry: entry['id'])
        report['completed'] = len(report['entries'])
        report['failed'] = sum(not entry['comparisonVerified'] for entry in report['entries'])
        report['elapsedSeconds'] = round(time.monotonic() - started, 3)
        temporary = out / 'report.json.part'
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(out / 'report.json')

    save()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        jobs = [pool.submit(individual, pair) for pair in enumerate(ids)]
        for job in concurrent.futures.as_completed(jobs):
            entry = job.result()
            report['entries'].append(entry)
            save()
            print(report['completed'], '/', len(ids), entry['id'],
                  'PASS' if entry['comparisonVerified'] else 'FAIL', entry['elapsedSeconds'], flush=True)
    report['allIdsVerified'] = ids == population and not report['failed']
    save()
    return bool(report['failed'])


if __name__ == '__main__':
    raise SystemExit(main())
