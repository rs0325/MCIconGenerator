"""rendererのbinary/source対応を固定Ubuntu snapshotのSources索引から生成する。"""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

SNAPSHOT = 'https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/'
SUITES = ('noble', 'noble-updates', 'noble-backports', 'noble-security')
COMPONENTS = ('main', 'restricted', 'universe', 'multiverse')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def paragraphs(text):
    """Deb822の継続行を保持し、Sourcesの各段落を返す。"""
    fields = {}
    key = None
    for line in text.splitlines() + ['']:
        if not line:
            if fields:
                yield fields
            fields = {}
            key = None
        elif line[0].isspace():
            if key is None:
                raise ValueError('継続行の先頭fieldがありません')
            fields[key] += '\n' + line[1:]
        else:
            key, value = line.split(':', 1)
            fields[key] = value.lstrip()


def source_identity(package):
    """dpkg Source省略時はbinary名/版、版省略時はbinary版を使用する。"""
    source = package.get('sourceField')
    if source is None:
        return package['package'], package['version']
    match = re.fullmatch(r'([a-z0-9][a-z0-9+.-]*)(?: \(([^() ]+)\))?', source)
    if not match:
        raise ValueError('Source fieldが不正です: ' + source)
    return match[1], match[2] or package['version']


def source_record(fields, base_url=SNAPSHOT):
    files = []
    for line in fields['Checksums-Sha256'].splitlines():
        if not line.strip():
            continue
        sha, size, name = line.split()
        if not re.fullmatch('[0-9a-f]{64}', sha) or int(size) <= 0:
            raise ValueError('Sourcesのchecksum/sizeが不正です')
        files.append({'name': name, 'bytes': int(size), 'sha256': sha,
                      'url': base_url + urllib.parse.quote((fields['Directory'] + '/' + name).lstrip('/'), safe='/+~.-_')})
    dsc = [f for f in files if f['name'].endswith('.dsc')]
    archives = [f for f in files if re.search(r'\.tar\.(?:gz|xz|bz2|lzma|zst)$', f['name'])]
    if len(dsc) != 1 or not archives:
        raise ValueError('sourceの.dscまたはtarballがありません')
    return {'package': fields['Package'], 'version': fields['Version'],
            'binaryNames': [name.strip() for name in fields['Binary'].replace('\n', ' ').split(',')],
            'directory': fields['Directory'], 'standardsVersion': fields.get('Standards-Version'),
            'format': fields.get('Format'), 'homepage': fields.get('Homepage'),
            'dsc': dsc[0], 'sourceArchives': archives,
            'files': files,
            'debianCopyrightInSource': '未確認（source archive本体は未取得）'}


def match_packages(packages, sources):
    result = []
    for package in packages:
        name, version = source_identity(package)
        source = sources.get((name, version))
        reason = None
        if source is None:
            reason = '固定snapshot Sourcesにsource名・版の完全一致がない'
        elif package['package'] not in source['binaryNames']:
            reason = 'sourceのBinary fieldにbinary名がない'
        result.append({**package, 'sourcePackage': name, 'sourceVersion': version,
                       'sourceId': name + '@' + version if reason is None else None,
                       'unmatchedReason': reason})
    return result


def generate(evidence_path, inventory_path, cache, offline=False, metadata_path=None, refs=(91, 93)):
    evidence_raw = evidence_path.read_bytes()
    evidence = json.loads(evidence_raw)
    inventory_raw = inventory_path.read_bytes()
    inventory = {}
    for line in inventory_raw.decode('utf-8').splitlines():
        name, version = line.split('\t')
        inventory[name.split(':', 1)[0]] = version
    installed = {p['package']: p['version'] for p in evidence['packages']}
    if inventory != installed:
        raise ValueError('image内の全package名/版が指定inventoryと一致しません')
    wanted = {source_identity(p) for p in evidence['packages']}
    sources = {}
    available_versions = {name: set() for name, _ in wanted}
    indexes = []
    cache.mkdir(parents=True, exist_ok=True)
    for suite in SUITES:
        for component in COMPONENTS:
            relative = f'dists/{suite}/{component}/source/Sources.gz'
            url = SNAPSHOT + relative
            local = cache / f'{suite}-{component}-Sources.gz'
            if not local.exists():
                if offline:
                    raise FileNotFoundError(local)
                print('取得: ' + url, flush=True)
                request = urllib.request.Request(url, headers={'User-Agent': 'MCIconGenerator-source-audit/1.1'})
                with urllib.request.urlopen(request, timeout=120) as response:
                    local.write_bytes(response.read())
            raw = local.read_bytes()
            plain = gzip.decompress(raw)
            indexes.append({'id': suite + '/' + component, 'url': url,
                            'bytes': len(raw), 'sha256': digest(raw),
                            'uncompressedSha256': digest(plain)})
            for fields in paragraphs(plain.decode('utf-8')):
                key = fields['Package'], fields['Version']
                if key[0] in available_versions:
                    available_versions[key[0]].add(key[1])
                if key not in wanted:
                    continue
                entry = source_record(fields)
                if key in sources:
                    previous = {k: v for k, v in sources[key].items() if k != 'indexes'}
                    if previous != entry:
                        raise ValueError('同一source名/版の索引内容が一致しません: ' + str(key))
                    sources[key]['indexes'].append(suite + '/' + component)
                else:
                    sources[key] = {**entry, 'indexes': [suite + '/' + component]}
    metadata = json.loads(metadata_path.read_bytes()) if metadata_path else None
    if metadata:
        for source in metadata['sources']:
            key = source['package'], source['version']
            if key not in wanted or key in sources:
                raise ValueError('補足sourceの対象が不正または索引と重複しています')
            sources[key] = source
    packages = match_packages(evidence['packages'], sources)
    for package in packages:
        if package['sourceId'] is None:
            package['availableSourceVersions'] = sorted(available_versions[package['sourcePackage']])
    return {'schemaVersion': 1, 'date': '2026-11-04', 'refs': list(refs),
            'imageId': evidence['imageId'], 'snapshot': SNAPSHOT,
            'evidenceSha256': digest(evidence_raw), 'binaryInventorySha256': digest(inventory_raw),
            'indexSignatureVerified': False,
            'sourceArchiveBytesVerified': False,
            'indexes': indexes, 'packages': packages,
            'sources': [{'id': name + '@' + version, **source}
                        for (name, version), source in sorted(sources.items())],
            'java': evidence['java'],
            'javaReleases': metadata['java'] if metadata else [],
            'supplementalMetadataSha256': digest(metadata_path.read_bytes()) if metadata_path else None,
            'unresolvedMetadata': metadata['unresolved'] if metadata else [],
            'dscChecks': metadata['dscChecks'] if metadata else [],
            'commonLicenses': evidence['commonLicenses'],
            'assetSearch': evidence['assetSearch'],
            'fileList': evidence['fileList'],
            'summary': {'packages': len(packages),
                        'matched': sum(p['sourceId'] is not None for p in packages),
                        'unmatched': sum(p['sourceId'] is None for p in packages),
                        'sourcePackages': len(sources),
                        'matchedFromIndexes': sum(bool(sources[(p['sourcePackage'], p['sourceVersion'])]['indexes'])
                                                  for p in packages if p['sourceId']),
                        'matchedFromDsc': sum(not sources[(p['sourcePackage'], p['sourceVersion'])]['indexes']
                                               for p in packages if p['sourceId']),
                        'copyrightMissing': [p['package'] for p in packages if p['copyright'] is None]}}


def supplement(table_path, metadata_path):
    """既存表の入力証拠を保持し、未一致版だけを補足.dscで解決する。"""
    raw = table_path.read_bytes().replace(b'\r\n', b'\n')
    table = json.loads(raw)
    metadata_raw = metadata_path.read_bytes()
    metadata = json.loads(metadata_raw)
    wanted = {(p['sourcePackage'], p['sourceVersion'])
              for p in table['packages'] if p['sourceId'] is None}
    sources = {(s['package'], s['version']): s for s in table['sources']}
    for source in metadata['sources']:
        key = source['package'], source['version']
        if key not in wanted or key in sources:
            raise ValueError('補足sourceの対象が不正または既存表と重複しています')
        sources[key] = {'id': '@'.join(key), **source}
    packages = match_packages(table['packages'], sources)
    for package in packages:
        if package['sourceId']:
            package.pop('availableSourceVersions', None)
    table.update(packages=packages, sources=[sources[k] for k in sorted(sources)],
                 baselineTableSha256=digest(raw), supplementalMetadataSha256=digest(metadata_raw),
                 javaReleases=metadata['java'], unresolvedMetadata=metadata['unresolved'])
    table['summary'].update(
        matched=sum(p['sourceId'] is not None for p in packages),
        unmatched=sum(p['sourceId'] is None for p in packages), sourcePackages=len(sources),
        matchedFromIndexes=sum(bool(sources[(p['sourcePackage'], p['sourceVersion'])]['indexes'])
                               for p in packages if p['sourceId']),
        matchedFromDsc=sum(not sources[(p['sourcePackage'], p['sourceVersion'])]['indexes']
                           for p in packages if p['sourceId']))
    return table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--evidence', type=Path)
    inputs.add_argument('--baseline', type=Path)
    parser.add_argument('--packages', type=Path)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--refs', nargs='+', type=int, default=[91, 93])
    parser.add_argument('--metadata', type=Path)
    args = parser.parse_args()
    if args.baseline:
        if not args.metadata or args.evidence or args.packages or args.cache:
            parser.error('--baselineには--metadataだけを指定します')
        result = supplement(args.baseline, args.metadata)
    else:
        if not args.packages or not args.cache:
            parser.error('--evidenceには--packagesと--cacheが必要です')
        result = generate(args.evidence, args.packages, args.cache, args.offline, args.metadata, args.refs)
    raw = encode(result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(raw)
    args.output.with_suffix(args.output.suffix + '.sha256').write_text(
        digest(raw) + '  ' + args.output.name + '\n', encoding='utf-8')
    print(json.dumps(result['summary'], ensure_ascii=False))
    if result['summary']['unmatched']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
