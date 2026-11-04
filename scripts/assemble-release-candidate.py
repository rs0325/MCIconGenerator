"""署名前の1.1.0候補を実archiveから組み立てる。秘密鍵・署名・ネットワークは使用しない。"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
HOSTS = {'windows-x86_64': ('windows', 'x86_64', '10.0.26200', 'MCIconHelper/MCIconHelper.exe'),
         'macos-aarch64': ('macos', 'aarch64', '15.8.1', 'MCIconHelper.app/Contents/MacOS/MCIconHelper')}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode('utf-8')


def save(path, value):
    raw = encode(value)
    maximum = 4 * 1024 * 1024 if path.parent.name == 'inventories' else 1024 * 1024
    if len(raw) > maximum:
        raise ValueError('JSONがJava側の読込み上限を超えています')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(raw)
    return {'path': path.as_posix(), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}


def adapter_digest(root):
    data = bytearray()
    for name in ('adapters/legacy-mappings.json', 'adapters/releases.json'):
        raw = (root / name).read_bytes()
        data.extend((name + '\0' + str(len(raw)) + '\0').encode('utf-8'))
        data.extend(raw)
    return hashlib.sha256(data).hexdigest()


def assemble(plan, output, root=ROOT):
    """2回の再生成とQA pinを照合し、署名前のdescriptor/inventoryを出力する。"""
    if output.exists():
        raise ValueError('既存出力は上書きしません')
    if plan['version'] != '1.1.0' or plan['tag'] != 'v1.1.0':
        raise ValueError('1.1.0/v1.1.0の候補だけを扱います')
    expected = {('host-helper', h) for h in HOSTS} | {('guest-base', h) for h in HOSTS} | {('renderer-image', 'linux-amd64')}
    rows = plan['artifacts']
    confirmed = plan.get('finalBytesConfirmed', False)
    if type(confirmed) is not bool:
        raise ValueError('最終bytes採用flagはbooleanが必要です')
    if confirmed and (plan['unresolved'] or any(
            row['contents'].get('licenseClosureConfirmed') is not True
            or not row['sourceArchives'] or not row['noticeFiles'] for row in rows)):
        raise ValueError('最終bytes採用には未解決事項なし・全資材のlicense/notice/source閉包が必要です')
    if len(rows) != 5 or {(r['role'], r['host']) for r in rows} != expected:
        raise ValueError('2hostのhelper/guestと共有rendererの5資材が必要です')
    checked = []
    ids = set()
    for row in rows:
        identifier = row['id']
        if not re.fullmatch('[a-z0-9][a-z0-9._-]{0,95}', identifier) or identifier in ids:
            raise ValueError('artifact IDが不正または重複しています')
        ids.add(identifier)
        fmt = {'host-helper': 'zip', 'renderer-image': 'tar.gz'}.get(row['role'], 'tar.xz' if row['host'] == 'windows-x86_64' else 'qcow2')
        name = 'mcicon-' + {'host-helper': 'helper', 'guest-base': 'guest', 'renderer-image': 'renderer'}[row['role']] + '-1.1.0-' + row['host'] + '.' + fmt
        if identifier + '.' + fmt != name:
            raise ValueError('artifact IDがRelease basenameと一致しません')
        runs = []
        for value in row['runs']:
            path = Path(value)
            if not path.is_file() or path.is_symlink():
                raise ValueError('実archiveの通常fileが必要です')
            runs.append({'bytes': path.stat().st_size, 'sha256': digest(path)})
        if len(runs) != 2 or runs[0] != runs[1] or runs[0] != row['baseline']:
            raise ValueError('再生成2回または既存QA pinと一致しません: ' + identifier)
        if type(row['unpackedBytes']) is not int or row['unpackedBytes'] < runs[0]['bytes']:
            raise ValueError('展開size予算が不正です')
        if not row['licenseFiles']:
            raise ValueError('license参照が必要です')
        checked.append((row, fmt, name, runs))
    output.mkdir(parents=True)
    artifacts = {}
    reports = []
    base = 'https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.0/'
    for row, fmt, name, runs in checked:
        archive = output / 'artifacts' / name
        archive.parent.mkdir(exist_ok=True)
        shutil.copyfile(row['runs'][0], archive)
        if {'bytes': archive.stat().st_size, 'sha256': digest(archive)} != runs[0]:
            raise ValueError('配置中にarchiveが変化しました')
        evidence = []
        for value in row['evidence']:
            path = root / value
            evidence.append({'path': value, 'bytes': path.stat().st_size, 'sha256': digest(path)})
        inventory_path = 'inventories/' + row['id'] + '.json'
        inventory = {'schemaVersion': 1, 'finalBytesConfirmed': confirmed, 'artifactId': row['id'],
                     'role': row['role'], 'artifact': {'url': base + name, **runs[0]},
                     'reproduction': {'runs': runs, 'baseline': row['baseline'], 'matched': True},
                     'evidence': evidence, 'unresolved': plan['unresolved'], 'contents': row['contents']}
        inventory_report = save(output / inventory_path, inventory)
        artifact = {'id': row['id'], 'role': row['role'], 'url': base + name, **runs[0],
                    'unpackedBytes': row['unpackedBytes'], 'format': fmt,
                    'inventoryPath': inventory_path, 'inventorySha256': inventory_report['sha256'],
                    'licenseFiles': row['licenseFiles'], 'noticeFiles': row['noticeFiles'],
                    'sourceArchives': row['sourceArchives']}
        artifacts[(row['role'], row['host'])] = artifact
        reports.append({'id': row['id'], 'basename': name, **runs[0], 'reproduction': 'PASS-2-runs-and-baseline',
                        'inventory': {**inventory_report, 'path': inventory_path}})
    worker = {'toolVersion': '1.1.0', 'protocolVersion': 1, 'agentSha256': digest(Path(plan['agent'])),
              'adapterSetSha256': adapter_digest(root)}
    entries = []
    sign = []
    inventory_resources = []
    renderer = artifacts[('renderer-image', 'linux-amd64')]
    for name, (os_name, arch, minimum, executable) in HOSTS.items():
        helper = artifacts[('host-helper', name)]
        renderer_path = 'inventories/' + name + '/' + Path(renderer['inventoryPath']).name
        target = output / renderer_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output / renderer['inventoryPath'], target)
        host_renderer = {**renderer, 'inventoryPath': renderer_path}
        inventory_resources.extend(a['inventoryPath'] for a in (helper, artifacts[('guest-base', name)], host_renderer))
        descriptor = {'schemaVersion': 1, 'runtimeId': 'mcicon-1.1.0-' + name, 'toolVersion': '1.1.0',
                      'host': {'os': os_name, 'arch': arch}, 'minimumOsVersion': minimum,
                      'helper': {'protocolVersion': 1, 'artifactId': helper['id'], 'executable': executable},
                      'worker': worker,
                      'rendering': {**plan['rendering'], 'imageArtifactId': renderer['id']},
                      'artifacts': [helper, artifacts[('guest-base', name)], host_renderer]}
        path = 'descriptors/' + name + '.json'
        report = {**save(output / path, descriptor), 'path': path}
        entries.append({'host': descriptor['host'], 'descriptorPath': path,
                        'descriptorSha256': report['sha256'], 'trustKeyPath': 'trust-set.bin'})
        sign.append(report)
    save(output / 'input.json', {'schemaVersion': 1, 'finalBytesConfirmed': confirmed, 'runtimes': entries})
    report = {'schemaVersion': 1, 'finalBytesConfirmed': confirmed, 'signatureVerified': False,
              'artifacts': reports, 'inventoryResourcePaths': inventory_resources, 'signingTargets': sign, 'unresolved': plan['unresolved']}
    save(output / 'candidate-report.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = assemble(json.loads(args.plan.read_bytes()), args.output)
    print(json.dumps({'finalBytesConfirmed': report['finalBytesConfirmed'], 'artifacts': len(report['artifacts']),
                      'signingTargets': report['signingTargets']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
