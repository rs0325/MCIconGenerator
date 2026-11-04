"""署名・採用済みruntimeと現coreからpayload ZIPと次の署名対象stable.jsonを生成する。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('candidate', ROOT / 'scripts/assemble-release-candidate.py')
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)
spec = importlib.util.spec_from_file_location('trust_set', ROOT / 'bootstrap/scripts/trust_set.py')
trust_set = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trust_set)


def verify_signature(raw, signature, trust, openssl):
    if len(signature) != 64:
        raise ValueError('64bytesの署名が必要です')
    with tempfile.TemporaryDirectory(prefix='mcicon-public-verify-') as folder:
        folder = Path(folder)
        (folder / 'input').write_bytes(raw)
        (folder / 'signature').write_bytes(signature)
        for index, public in enumerate((trust[8:52], trust[52:96])):
            key = folder / ('public-' + str(index) + '.der')
            key.write_bytes(public)
            result = subprocess.run([openssl, 'pkeyutl', '-verify', '-rawin', '-pubin', '-keyform', 'DER',
                                     '-inkey', str(key), '-in', str(folder / 'input'),
                                     '-sigfile', str(folder / 'signature')], capture_output=True, timeout=30)
            if result.returncode == 0:
                return
    raise ValueError('公開trustのいずれの鍵でも署名を検証できません')


def core_resources(core, resources):
    expected = {p.relative_to(resources).as_posix(): p.read_bytes()
                for p in resources.rglob('*') if p.is_file()}
    if not expected or any(p.is_symlink() for p in resources.rglob('*')):
        raise ValueError('生成済みruntime resourceが必要です')
    with zipfile.ZipFile(core) as jar:
        names = [n for n in jar.namelist() if n.startswith('META-INF/mcicon/runtime/') and not n.endswith('/')]
        if len(names) != len(set(names)) or set(names) != set(expected) or any(jar.read(n) != expected[n] for n in names):
            raise ValueError('現core JARと生成resourceが一致しません')
    return expected


def prepare(args):
    definition = json.loads((args.runtime_input / 'input.json').read_bytes())
    if definition['finalBytesConfirmed'] is not True:
        raise ValueError('採用済み最終runtime inputが必要です。候補flagは自動変更しません')
    if args.sequence < 1 or args.output.exists():
        raise ValueError('正のsequenceと未使用の出力先が必要です')
    trust = trust_set.read(args.runtime_input / 'trust-set.bin')
    expected = core_resources(args.core, args.resources)
    if (args.cli_lib / 'core-1.1.0.jar').read_bytes() != args.core.read_bytes():
        raise ValueError('CLI内coreと現在core JARが一致しません')
    pins = json.loads(expected['META-INF/mcicon/runtime/pins.json'])
    if pins['runtimes'] != definition['runtimes']:
        raise ValueError('core pinsと採用inputのruntime集合が一致しません')
    entries = {r['host']['os'] + '-' + r['host']['arch']: r for r in definition['runtimes']}
    if len(definition['runtimes']) != 2 or set(entries) != set(candidate.HOSTS):
        raise ValueError('確定済み2hostのinputが必要です')
    checked = {}
    for host, entry in entries.items():
        path = entry['descriptorPath']
        raw = (args.runtime_input / path).read_bytes()
        signature = (args.runtime_input / (path + '.sig')).read_bytes()
        if hashlib.sha256(raw).hexdigest() != entry['descriptorSha256']:
            raise ValueError('descriptor pin不一致')
        prefix = 'META-INF/mcicon/runtime/'
        if expected.get(prefix + path) != raw or expected.get(prefix + path + '.sig') != signature:
            raise ValueError('descriptor署名とcore resourceが一致しません')
        if expected.get(prefix + entry['trustKeyPath']) != trust:
            raise ValueError('coreとpackageのtrustが一致しません')
        verify_signature(raw, signature, trust, args.openssl)
        descriptor = json.loads(raw)
        if descriptor['host'] != entry['host'] or descriptor['toolVersion'] != '1.1.0':
            raise ValueError('descriptorのhost/版が不一致です')
        if candidate.digest(args.worker) != descriptor['worker']['agentSha256']:
            raise ValueError('agent pin不一致')
        helper = next(a for a in descriptor['artifacts'] if a['role'] == 'host-helper')
        archive = args.runtime_input / 'artifacts' / (helper['id'] + '.' + helper['format'])
        if archive.stat().st_size != helper['bytes'] or candidate.digest(archive) != helper['sha256']:
            raise ValueError('helper bytes不一致')
        checked[host] = (entry, raw, signature, archive, helper)
    args.output.mkdir(parents=True)
    platforms = []
    for host, (entry, raw, signature, archive, helper_definition) in checked.items():
        files = {}
        for p in sorted(args.cli_lib.glob('*.jar')):
            if p.is_symlink():
                raise ValueError('CLI JARのlinkは使用できません')
            files['app/lib/' + p.name] = (p.read_bytes(), 0o100644)
        for name in ('cli', 'core', 'worker'):
            if 'app/lib/' + name + '-1.1.0.jar' not in files:
                raise ValueError('CLI distributionが不完全です')
        files['app/worker/worker-agent.jar'] = (args.worker.read_bytes(), 0o100644)
        files[entry['descriptorPath']] = (raw, 0o100644)
        files[entry['descriptorPath'] + '.sig'] = (signature, 0o100644)
        files['trust-set.bin'] = (trust, 0o100644)
        for p in [ROOT / 'LICENSE', ROOT / 'THIRD_PARTY_NOTICES.md', *sorted((ROOT / 'licenses').glob('*'))]:
            if p.is_file():
                files[p.relative_to(ROOT).as_posix()] = (p.read_bytes(), 0o100644)
        runtime_prefix = ('MCIconHelper/runtime/' if host.startswith('windows') else
                          'MCIconHelper.app/Contents/runtime/Contents/Home/')
        documents = {'licenseFiles': ['LICENSE'], 'noticeFiles': ['THIRD_PARTY_NOTICES.md']}
        with zipfile.ZipFile(archive) as helper:
            for field in documents:
                for path in sorted(set(helper_definition[field])):
                    if path.startswith(runtime_prefix):
                        documents[field].append('java/' + path[len(runtime_prefix):])
                    elif path.startswith(('MCIconHelper/', 'MCIconHelper.app/')):
                        name = 'licenses/runtime-helper/' + path
                        files[name] = (helper.read(path), 0o100644)
                        documents[field].append(name)
            for item in helper.infolist():
                if item.filename.startswith(runtime_prefix) and not item.is_dir():
                    name = 'java/' + item.filename[len(runtime_prefix):]
                    if '..' in name.split('/') or name in files or (item.external_attr >> 16) & 0o170000 == 0o120000:
                        raise ValueError('JRE entryが不正または重複しています')
                    files[name] = (helper.read(item), (item.external_attr >> 16) or 0o100644)
        java = 'java/bin/' + ('java.exe' if host.startswith('windows') else 'java')
        if java not in files:
            raise ValueError('自己完結Javaがありません')
        inventory_path = 'inventories/tool-' + host + '.json'
        inventory = candidate.encode({'schemaVersion': 1, 'files': [
            {'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
            for name, (data, _) in sorted(files.items())]})
        if len(inventory) > 4 * 1024 * 1024:
            raise ValueError('payload inventoryが4MiBを超えています')
        files[inventory_path] = (inventory, 0o100644)
        archive_name = 'mcicon-tool-1.1.0-' + host + '.zip'
        payload = args.output / archive_name
        with zipfile.ZipFile(payload, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=3) as out:
            for name, (data, mode) in sorted(files.items()):
                item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                item.create_system = 3
                item.external_attr = mode << 16
                item.compress_type = zipfile.ZIP_DEFLATED
                out.writestr(item, data, compresslevel=3)
        platforms.append({'host': entry['host'], 'runtimeDescriptor': {'path': entry['descriptorPath'], 'sha256': entry['descriptorSha256']},
                          'payload': {'id': 'mcicon-tool-1.1.0-' + host, 'role': 'tool-payload',
                                      'url': 'https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.0/' + archive_name,
                                      'bytes': payload.stat().st_size, 'sha256': candidate.digest(payload),
                                      'unpackedBytes': sum(len(data) for data, _ in files.values()), 'format': 'zip',
                                      'inventoryPath': inventory_path, 'inventorySha256': hashlib.sha256(inventory).hexdigest(),
                                      'licenseFiles': documents['licenseFiles'], 'noticeFiles': documents['noticeFiles'],
                                      'sourceArchives': helper_definition['sourceArchives']}})
    candidate.save(args.output / 'stable.json', {'schemaVersion': 1, 'channel': 'stable', 'sequence': args.sequence,
                                                'toolVersion': '1.1.0', 'minimumBootstrapProtocol': 4, 'platforms': platforms})
    print('payload/stable生成済み。stable署名・seed/packageのJava検証・配布gateは未完了です')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime-input', 'cli-lib', 'worker', 'core', 'resources', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--sequence', type=int, required=True)
    parser.add_argument('--openssl', default='openssl')
    prepare(parser.parse_args())


if __name__ == '__main__':
    main()
