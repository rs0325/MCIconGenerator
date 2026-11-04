"""固定Mac guest manifestとdeb controlをUbuntu Sourcesへ対応させる。正式inventoryは発行しない。"""

import argparse
import concurrent.futures
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import urllib.request
import urllib.parse


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def generate(root, inputs, deb_documents, environment, cache, offline=False):
    measured = json.loads(environment.read_bytes())
    spec = importlib.util.spec_from_file_location('ubuntu_sources', root / 'docker/map-sources.py')
    mapping = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mapping)
    snapshot = 'https://snapshot.ubuntu.com/ubuntu/20261017T000000Z/'
    cache.mkdir(parents=True, exist_ok=True)

    def get(relative):
        path = cache / relative.replace('/', '_')
        if not path.exists():
            if offline:
                raise FileNotFoundError(path)
            with urllib.request.urlopen(snapshot + relative, timeout=120) as response:
                path.write_bytes(response.read())
        return path.read_bytes()

    paths = [f'dists/{suite}/{component}/{kind}'
             for suite in ('noble', 'noble-updates', 'noble-security')
             for component in ('main', 'universe', 'restricted')
             for kind in ('binary-arm64/Packages.gz', 'source/Sources.gz')]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        bodies = list(pool.map(get, paths))
    indexes = []
    binaries = {}
    sources = {}
    for relative, raw in zip(paths, bodies):
        indexes.append({'url': snapshot + relative, 'bytes': len(raw), 'sha256': digest(raw)})
        for fields in mapping.paragraphs(gzip.decompress(raw).decode()):
            key = fields['Package'], fields['Version']
            if relative.endswith('Sources.gz'):
                sources[key] = mapping.source_record(fields, snapshot)
            else:
                binaries[key] = fields

    packages = []
    for line in (inputs / 'base.manifest').read_text().splitlines():
        name, version = line.split('\t')
        name = name.split(':')[0]
        fields = binaries.get((name, version))
        packages.append({'package': name, 'version': version, 'origin': 'guest-manifest',
                         'sourceField': fields.get('Source') if fields else None,
                         'binaryIndexExactMatch': fields is not None})
    for deb in json.loads((deb_documents / 'debs.json').read_bytes()):
        control = next(mapping.paragraphs(deb['control']))
        packages.append({'package': control['Package'], 'version': control['Version'],
                         'origin': 'offline-deb-control', 'sourceField': control.get('Source'),
                         'copyright': {'bytes': deb['copyrightBytes'], 'sha256': deb['copyrightSha256']}})
    for package in packages:
        if package.get('binaryIndexExactMatch') is False:
            # 古いbinary版は、SourcesのBinary名と版が完全一致する場合だけ採る。
            matches = [s for s in sources.values() if package['package'] in s['binaryNames'] and package['version'] == s['version']]
            if len(matches) == 1:
                package['sourceField'] = matches[0]['package'] + ' (' + matches[0]['version'] + ')'
    matched = mapping.match_packages(packages, sources)
    for package in matched:
        if package.get('binaryIndexExactMatch') is False and package['sourceField'] is None:
            package.update(sourceId=None, unmatchedReason='固定snapshotにbinary版がなく、SourcesのBinary名/版でも確定できない')
    spec = importlib.util.spec_from_file_location('source_metadata', root / 'docker/fetch-source-metadata.py')
    metadata = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(metadata)
    supplemental_cache = cache / 'supplemental'
    supplemental_cache.mkdir(exist_ok=True)
    candidates = {}
    for package in matched:
        if package['sourceId']:
            continue
        names = {mapping.source_identity({'package': f['Package'], 'version': f['Version'], 'sourceField': f.get('Source')})[0]
                 for f in binaries.values() if f['Package'] == package['package']}
        if package.get('binaryIndexExactMatch'):
            names = {package['sourcePackage']}
        if len(names) == 1:
            name = next(iter(names))
            candidates[(name, package['version'])] = next((s['directory'] for s in sources.values() if s['package'] == name), None)

    def supplement(item):
        (name, version), directory = item
        result_path = supplemental_cache / (digest((name + '@' + version).encode()) + '.json')
        if result_path.exists():
            return json.loads(result_path.read_bytes())
        if offline:
            raise FileNotFoundError(result_path)
        filename = name + '_' + version.split(':')[-1] + '.dsc'
        locations = []
        if directory:
            relative = urllib.parse.quote(directory + '/' + filename, safe='/+~.-_')
            locations.extend(base + relative for base in (snapshot, 'https://archive.ubuntu.com/ubuntu/'))
        locations.append('https://launchpad.net/ubuntu/+archive/primary/+sourcefiles/' + name + '/' + urllib.parse.quote(version, safe=':+~.-_') + '/' + filename)
        table = {'packages': [{'sourcePackage': name, 'sourceVersion': version, 'sourceId': None}], 'javaReleases': [], 'dscChecks': []}
        result = metadata.collect_locations(table, supplemental_cache, {name + '@' + version: locations})
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        supplements = list(pool.map(supplement, candidates.items()))
    for result in supplements:
        for source in result['sources']:
            sources[source['package'], source['version']] = source
    for package in matched:
        if package['sourceId']:
            continue
        exact = [s for s in sources.values() if package['package'] in s['binaryNames'] and package['version'] == s['version']]
        if len(exact) == 1:
            source = exact[0]
            package.update(sourcePackage=source['package'], sourceVersion=source['version'],
                           sourceId=source['package'] + '@' + source['version'], unmatchedReason=None,
                           sourceIdentityEvidence='取得した.dscのBinary名/版が完全一致')
    used = {(p['sourcePackage'], p['sourceVersion']) for p in matched if p['sourceId']}
    selected = [{'id': name + '@' + version, **sources[(name, version)]} for name, version in sorted(used)]
    java_path = supplemental_cache / 'java.json'
    if not java_path.exists():
        if offline:
            raise FileNotFoundError(java_path)
        java = metadata.collect({'packages': [], 'sources': [], 'javaReleases': [], 'dscChecks': [],
                                 'java': [{'home': 'helper-runtime', 'release': measured['jdkRelease']}]}, supplemental_cache)
        java_path.write_text(json.dumps(java['java'], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lima_binary = (inputs / 'lima.tar.gz').read_bytes()
    lima_source = (inputs / 'lima-source.tar.gz').read_bytes()
    java = json.loads(java_path.read_bytes())
    release_raw, _ = metadata.fetch(java[0]['metadata'][0]['url'], supplemental_cache, offline)
    binary = next(a for a in json.loads(release_raw)['assets'] if '-jdk_aarch64_mac_hotspot_' in a['name'] and a['name'].endswith('.tar.gz'))
    for file, expected in [('jdk-source.tar.gz', java[0]['sourceArchive']),
                           ('jdk.tar.gz', {'url': binary['browser_download_url'], 'bytes': binary['size'], 'sha256': binary['digest'].removeprefix('sha256:')})]:
        with (inputs / file).open('rb') as stream:
            actual_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual_sha != expected['sha256'] or (inputs / file).stat().st_size != expected['bytes']:
            raise ValueError('Temurin archiveが公式release metadataと一致しません')
        expected['bytesVerified'] = True
        if file == 'jdk.tar.gz':
            java[0]['binaryArchive'] = expected
    libraries = []
    for name, version in [('jackson-annotations', '2.22'), ('jackson-core', '2.22.3'), ('jackson-databind', '2.22.3')]:
        url = f'https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/{name}/{version}/{name}-{version}-sources.jar'
        _, pin = metadata.fetch(url, supplemental_cache, offline)
        libraries.append({'name': name, 'version': version, 'sourceArchive': {**pin, 'bytesVerified': True}})
    project_files = []
    for name in ('host-probe.swift', 'process-guard.c', 'cpu-probe.S'):
        path = root / 'helpers/macos/src/main/native' / name
        raw = subprocess.check_output(['git', '-C', str(root), 'show', measured['sourceCommit'] + ':' + path.relative_to(root).as_posix()])
        if path.read_bytes().replace(b'\r\n', b'\n') != raw.replace(b'\r\n', b'\n'):
            raise ValueError('native sourceが測定時commitと一致しません')
        project_files.append({'path': path.relative_to(root).as_posix(), 'bytes': len(raw), 'sha256': digest(raw)})
    return {'schemaVersion': 1, 'date': '2026-11-04', 'refs': [91, 94],
            'snapshot': snapshot, 'guestManifest': {'bytes': (inputs / 'base.manifest').stat().st_size,
            'sha256': digest((inputs / 'base.manifest').read_bytes())},
            'debDocumentsSha256': digest((deb_documents / 'debs.json').read_bytes()),
            'java': java,
            'javaLibraries': libraries,
            'lima': {'version': '2.2.1', 'binary': {'url': 'https://github.com/lima-vm/lima/releases/download/v2.2.1/lima-2.2.1-Darwin-arm64.tar.gz', 'bytes': len(lima_binary), 'sha256': digest(lima_binary)},
                     'source': {'url': 'https://github.com/lima-vm/lima/archive/refs/tags/v2.2.1.tar.gz', 'tag': 'v2.2.1', 'bytes': len(lima_source), 'sha256': digest(lima_source), 'bytesVerified': True}},
            'indexSignatureVerified': False, 'sourceArchiveBytesVerified': False,
            'indexes': indexes, 'packages': matched, 'sources': selected,
            'supplementalUnresolved': [u for result in supplements for u in result['unresolved']],
            'project': {'license': 'MIT', 'sourceCommit': measured['sourceCommit'], 'files': project_files},
            'osProvided': {'redistributed': False, 'components': ['Rosetta', 'Virtualization.framework', 'Apple system frameworks']},
            'limits': ['guest内dpkg status/copyright本文は未採取。cloud manifestと索引の対応であり全file監査ではない',
                       'LimaのGo依存とTemurinの全source/notice bundleは別途集約が必要',
                       'source archiveは索引記載URL/size/SHAであり本体を再取得した証拠ではない'],
            'summary': {'packages': len(matched), 'matched': sum(p['sourceId'] is not None for p in matched),
                        'unmatched': sum(p['sourceId'] is None for p in matched), 'sourcePackages': len(selected)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--deb-documents', type=Path, required=True)
    parser.add_argument('--environment', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    result = generate(root, args.inputs, args.deb_documents, args.environment, args.cache, args.offline)
    raw = (json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode()
    args.output.write_bytes(raw)
    print(json.dumps(result['summary'], ensure_ascii=False))
    if result['summary']['unmatched']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
