#!/usr/bin/env python3
"""ホストJava CLIの抽出・Docker描画・反復を確認し、ゲーム資産はローカルへ保存する。"""
import argparse
import hashlib
import importlib.util
import json
import platform
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('audit_export', repo / 'scripts/audit-export.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def verify_output(manifest, source, comparison=None, ids=None, require_renderer=True):
    """全件または指定集合の成功、入力hash、画像・catalog・描画条件の一致を検査する。"""
    m = json.loads(manifest.read_text(encoding='utf-8'))
    result = audit.audit(manifest, source_jar=source if comparison is None else None,
                         compare=comparison, require_all=comparison is not None and ids is None)
    record = {'audit': result, 'sourceSha256': m['sourceSha256'],
              'manifestSha256': hashlib.sha256(manifest.read_bytes()).hexdigest(),
              'sourceMatches': m['sourceSha256'] == hashlib.sha256(source.read_bytes()).hexdigest(),
              'complete': m['complete'] and m['counts']['failed'] == 0}
    if comparison is None:
        expected = {}
        with zipfile.ZipFile(source) as jar:
            for entry in jar.infolist():
                parts = entry.filename.split('/', 3)
                if not entry.is_dir() and len(parts) == 4 and parts[0] == 'assets' and parts[2] == 'textures' and parts[3].startswith(('item/', 'block/')) and parts[3].endswith('.png'):
                    expected[parts[1] + ':' + parts[3][:-4]] = entry.filename
        record['outputSetMatches'] = {e['id']: e['path'] for e in m['entries']} == expected
    else:
        baseline = json.loads(comparison.read_text(encoding='utf-8'))
        expected = set(ids) if ids is not None else {e['id'] for e in baseline['entries']}
        record['outputSetMatches'] = {e['id'] for e in m['entries']} == expected
        for key in ['settings', 'settingsDigest', 'context', 'environment', 'renderer', 'catalog']:
            record[key + 'Equal'] = m[key] == baseline[key]
        record['rendererRequired'] = require_renderer
        record['renderer'] = m['renderer']
        record['baselineRenderer'] = baseline['renderer']
        record['baselineSourceMatches'] = m['sourceSha256'] == baseline['sourceSha256']
    checks = ['sourceMatches', 'complete', 'outputSetMatches']
    if comparison is not None:
        checks += ['baselineSourceMatches', 'settingsEqual', 'settingsDigestEqual', 'contextEqual', 'environmentEqual', 'catalogEqual']
        if require_renderer:
            checks.append('rendererEqual')
    record['passed'] = not result['failures'] and all(record[k] for k in checks)
    return record


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--cache', type=Path, default=repo / '.local/cache')
    p.add_argument('--distribution', type=Path, default=repo / 'cli/build/install/mcicon')
    p.add_argument('--versions', nargs='+', default=['1.13', '1.21.4', '26.3'])
    p.add_argument('--compare', type=Path, required=True, help='同じworkerの正常全件出力')
    p.add_argument('--docker-context', default='colima-mcicon')
    p.add_argument('--docker-image', default='mcicon-render:dev')
    p.add_argument('--allow-renderer-difference', action='store_true', help='全件の他host比較ではrenderer名の差を記録し、RGBAは厳密比較する。反復はrenderer一致も必須。')
    a = p.parse_args()
    known = {v['id'] for v in json.loads((repo / 'adapters/releases.json').read_text(encoding='utf-8'))['versions']}
    if len(a.versions) != len(set(a.versions)) or any(v not in known for v in a.versions):
        p.error('固定正式集合の重複しないexact version IDを指定してください')
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    ids = 'stone cobblestone_stairs oak_slab oak_fence glass oak_leaves grass_block diamond bow compass clock potion creeper_spawn_egg leather_chestplate chest ender_chest white_bed white_banner shield player_head dragon_head white_shulker_box prismarine enchanted_book'.split()
    report = {'scope': 'ホストのJava CLI・raw抽出・Linux amd64 Docker描画。native/GPU/ARM backendの受入ではない。',
              'host': {'system': platform.system(), 'release': platform.release(), 'machine': platform.machine(), 'python': platform.python_version()},
              'java': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip(),
              'docker': json.loads(subprocess.run(['docker', '--context', a.docker_context, 'version', '--format', '{{json .}}'], capture_output=True, text=True, check=True).stdout),
              'harnessSourcesSha256': {str(f.relative_to(repo)): sha(f) for f in [Path(__file__).resolve(), repo / 'scripts/audit-export.py']},
              'allowRendererDifference': a.allow_renderer_difference,
              'jobs': [], 'passed': False}
    save = lambda: (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with tempfile.TemporaryDirectory(prefix='.cli-snapshot-', dir=out) as temporary:
        distribution = Path(temporary) / 'mcicon'
        shutil.copytree(a.distribution.resolve(), distribution)
        report['cliCoreSha256'] = sha(next((distribution / 'lib').glob('core-*.jar')))
        report['productionAgentSha256'] = sha(distribution / 'worker/worker-agent.jar')
        # Java自身のclasspath展開を使い、ホストのshellやbatへ依存しない。
        cli = ['java', '-cp', str(distribution / 'lib' / '*'), 'io.github.rs0325.mcicon.cli.Main']
        save()
        for version in a.versions:
            for kind in ['raw', 'icons', 'repeat']:
                folder = out / kind
                logs = out / 'logs'
                logs.mkdir(exist_ok=True)
                command = [*cli, 'extract' if kind == 'raw' else 'render', '--version', version,
                           '--cache', str(a.cache.resolve()), '--out', str(folder), '--offline', '--json', '--debug']
                if kind == 'raw' or kind == 'icons':
                    command += ['--all']
                else:
                    for item in ids:
                        command += ['--id', item]
                if kind != 'raw':
                    command += ['--allow-experimental', '--docker-context', a.docker_context, '--docker-image', a.docker_image]
                with (logs / (version + '-' + kind + '.json')).open('w') as stdout, (logs / (version + '-' + kind + '.log')).open('w') as stderr:
                    run = subprocess.run(command, stdout=stdout, stderr=stderr)
                mode = 'raw' if kind == 'raw' else 'icons'
                manifest = folder / 'java' / version / mode / 'manifest.json'
                job = {'version': version, 'kind': kind, 'exitCode': run.returncode, 'passed': False}
                if run.returncode == 0:
                    comparison = None if kind == 'raw' else (a.compare.resolve() if kind == 'icons' else out / 'icons') / 'java' / version / mode / 'manifest.json'
                    try:
                        job.update(verify_output(manifest, a.cache.resolve() / 'versions' / version / 'client.jar',
                                                 comparison, ['minecraft:' + i for i in ids] if kind == 'repeat' else None,
                                                 require_renderer=kind != 'icons' or not a.allow_renderer_difference))
                    except Exception as error:
                        job['error'] = type(error).__name__ + ': ' + str(error)
                report['jobs'].append(job)
                save()
                print(version, kind, 'PASS' if job['passed'] else 'FAIL', flush=True)
    report['passed'] = all(j['passed'] for j in report['jobs'])
    save()
    return not report['passed']


if __name__ == '__main__':
    raise SystemExit(main())
