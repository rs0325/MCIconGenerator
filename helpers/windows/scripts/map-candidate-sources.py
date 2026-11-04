"""Windows候補の実JDK/JARを既存の固定source記録へ対応させる。通信しない。"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
from native_artifact import verify

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('source_mapping', ROOT / 'docker/map-sources.py')
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


def generate(measurement, jdk, inputs, source_inputs, native_build):
    measured = json.loads(measurement.read_bytes())
    pins = json.loads(source_inputs.read_bytes())
    for name, candidate in pins['archives'].items():
        archive = inputs / 'sources' / candidate['fileName']
        if archive.stat().st_size != candidate['bytes'] or mapping.digest(archive.read_bytes()) != candidate['sha256']:
            raise ValueError('source archive pin不一致: ' + name)
    release = dict(line.split('=', 1) for line in (jdk / 'release').read_text(encoding='utf-8').splitlines() if '=' in line)
    previous = json.loads((ROOT / 'qa/renderer-sources-2026-11-04-b2b.json').read_bytes())
    java = next(j for j in previous['javaReleases'] if j['version'] == release['JAVA_RUNTIME_VERSION'].strip('"').removesuffix('-LTS'))
    if java['imageSource'] != release['SOURCE'].strip('"'):
        raise ValueError('実JDKとsource commitが一致しません')
    java = {**java, 'home': 'helper-runtime', 'sourceArchive': dict(java['sourceArchive'])}
    source = inputs / 'jdk-source.tar.gz'
    java['sourceArchive']['bytesVerified'] = source.stat().st_size == java['sourceArchive']['bytes'] and mapping.digest(source.read_bytes()) == java['sourceArchive']['sha256']
    if not java['sourceArchive']['bytesVerified']:
        raise ValueError('JDK source archive pin不一致')
    java['binaryVerification'] = json.loads((inputs / 'jdk-verification.json').read_bytes())
    if not java['binaryVerification']['bytesVerified'] or java['binaryVerification']['differences']:
        raise ValueError('JDK binary archiveと実JDKの照合が未完了です')
    components = [{'id': 'temurin-jre', 'sourceIdentityMatched': True, 'source': java}]
    known = {r['name']: r for r in json.loads((ROOT / 'qa/mac-helper-sources-2026-11-04.json').read_bytes())['javaLibraries']}
    for jar in measured['jars']:
        name, version = jar['path'].removesuffix('.jar').rsplit('-', 1)
        if name in ('helper-common', 'helper-windows'):
            continue
        source = known.get(name)
        if name in pins['archives']:
            candidate = pins['archives'][name]
            source = {'version': candidate['version'], 'sourceArchive': candidate}
        matched = source is not None and source['version'] == version
        components.append({'id': name, 'version': version, 'binary': jar,
                           'sourceIdentityMatched': matched,
                           'source': source if matched else None,
                           'unresolved': None if matched else 'source archiveのURL/bytes/SHAとbundle参照は未固定'})
    commit = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    for name, path in [('helper-windows', 'helpers/windows/src/main/java'),
                       ('helper-common', 'helpers/common/src/main/java'),
                       ('guest-bridge', 'helpers/windows/src/main/guest/guestbridge.py')]:
        files = sorted((ROOT / path).rglob('*.java')) if (ROOT / path).is_dir() else [ROOT / path]
        rows = []
        for file in files:
            relative = file.relative_to(ROOT).as_posix()
            original = subprocess.check_output(['git', '-C', str(ROOT), 'show', commit + ':' + relative])
            checkout = file.read_bytes()
            if checkout.replace(b'\r\n', b'\n') != original.replace(b'\r\n', b'\n'):
                raise ValueError('測定sourceがcommitと不一致')
            rows.append({'path': relative, 'gitSha256': mapping.digest(original), 'checkoutSha256': mapping.digest(checkout)})
        components.append({'id': name, 'sourceIdentityMatched': True, 'source': {'commit': commit, 'license': 'MIT', 'files': rows}})
    microsoft = [r for r in measured['files'] if r['path'].startswith('runtime/bin/') and
                 Path(r['path']).name.startswith(('api-ms-', 'ucrtbase', 'msvcp', 'vcruntime'))]
    components.append({'id': 'microsoft-runtime', 'sourceIdentityMatched': False, 'source': None,
                       'files': microsoft, 'redistributed': True,
                       'legalReview': pins['microsoftRuntime'],
                       'unresolved': 'Temurin ZIP由来の同梱Microsoft DLL。再配布資格・適用license・notice/source提供要否は法務判断が必要。配布可能とは確定しない'})
    native_source = ROOT / 'helpers/windows/src/main/native/windows-host.cpp'
    native = verify(native_build.parent / 'windows-host.exe', native_source, native_build)
    if native['toolchainArchiveSha256'] != pins['toolchain']['sha256']:
        raise ValueError('nativeとsource表のtoolchain不一致')
    original = subprocess.check_output(['git', '-C', str(ROOT), 'show', commit + ':helpers/windows/src/main/native/windows-host.cpp'])
    if original.replace(b'\r\n', b'\n') != native_source.read_bytes().replace(b'\r\n', b'\n'):
        raise ValueError('native sourceがcommitと不一致')
    components.append({'id': 'native-runtime', 'sourceIdentityMatched': True,
                       'source': {'commit': commit, 'gitSha256': mapping.digest(original), 'checkoutSha256': native['sourceSha256'],
                                  'toolchain': pins['toolchain'], 'upstream': {name: pins['archives'][name] for name in ['llvm-mingw', 'llvm-project', 'mingw-w64']},
                                  'runtimeLicenses': pins['runtimeLicenses'], 'notices': native['notices'],
                                  'identityBasis': '公式release archiveのdigestと同版build recipeのsource参照を照合。toolchain自身のsource rebuild/bit一致は未検証'},
                       'binary': native['artifact'], 'buildManifestSha256': mapping.digest(native_build.read_bytes())})
    return {'schemaVersion': 1, 'date': '2026-11-04', 'refs': [91, 95],
            'scope': 'native/C1 bridge結合済み候補。正式inventory/source bundleではない',
            'sourceCommit': commit, 'components': components,
            'sourceInputs': {'path': source_inputs.name, 'sha256': mapping.digest(source_inputs.read_bytes())},
            'publicSourceBundleReferencesConfirmed': False,
            'osProvided': {'redistributed': False, 'components': ['同梱DLL以外のWindows system DLL', 'WSL OS機能/kernel']},
            'reusedEvidence': ['qa/renderer-sources-2026-11-04-b2b.json', 'qa/mac-helper-sources-2026-11-04.json'],
            'summary': {'components': len(components), 'matched': sum(r['sourceIdentityMatched'] for r in components),
                        'unmatched': sum(not r['sourceIdentityMatched'] for r in components)}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--measurement', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--source-inputs', type=Path, required=True)
    parser.add_argument('--native-build', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = generate(args.measurement, args.jdk, args.inputs, args.source_inputs, args.native_build)
    args.output.write_bytes(mapping.encode(result))
    print(json.dumps(result['summary']))
