"""Sources索引で欠けた版の.dscと、Adoptium公式release metadataを採取する。"""

import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location('mapping', Path(__file__).with_name('map-sources.py'))
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


def fetch(url, cache):
    local = cache / (hashlib.sha256(url.encode()).hexdigest() + '.metadata')
    if not local.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'MCIconGenerator-source-audit/1.1'})
        with urllib.request.urlopen(request, timeout=120) as response:
            local.write_bytes(response.read())
    raw = local.read_bytes()
    return raw, {'url': url, 'bytes': len(raw), 'sha256': mapping.digest(raw)}


def dsc_fields(raw):
    text = raw.decode('utf-8')
    if text.startswith('-----BEGIN PGP SIGNED MESSAGE-----'):
        text = text.split('\n\n', 1)[1].split('-----BEGIN PGP SIGNATURE-----', 1)[0]
        text = '\n'.join(line[2:] if line.startswith('- ') else line for line in text.splitlines())
    return next(mapping.paragraphs(text))


def collect(table, cache):
    cache.mkdir(parents=True, exist_ok=True)
    directories = {}
    for local in sorted((cache.parent / 'indexes').glob('*-Sources.gz')):
        for fields in mapping.paragraphs(gzip.decompress(local.read_bytes()).decode('utf-8')):
            directories[fields['Package']] = fields['Directory']
    supplemental = []
    unresolved = []
    missing = sorted({(p['sourcePackage'], p['sourceVersion'])
                      for p in table['packages'] if p['sourceId'] is None})
    for name, version in missing:
        directory = directories[name]
        filename = name + '_' + version.split(':')[-1] + '.dsc'
        url = mapping.SNAPSHOT + urllib.parse.quote(directory + '/' + filename, safe='/+~.-_')
        attempts = []
        for base in (mapping.SNAPSHOT, 'https://archive.ubuntu.com/ubuntu/'):
            url = base + urllib.parse.quote(directory + '/' + filename, safe='/+~.-_')
            try:
                raw, pin = fetch(url, cache)
                break
            except urllib.error.HTTPError as error:
                attempts.append({'url': url, 'httpStatus': error.code})
        else:
            unresolved.append({'package': name, 'version': version, 'attempts': attempts})
            continue
        fields = dsc_fields(raw)
        if fields['Source'] != name or fields['Version'] != version:
            raise ValueError('取得した.dscのsource名/版が一致しません')
        fields['Package'] = fields['Source']
        fields['Directory'] = directory
        fields['Checksums-Sha256'] += '\n' + pin['sha256'] + ' ' + str(pin['bytes']) + ' ' + filename
        source = mapping.source_record(fields)
        for file in source['files']:
            file['url'] = file['url'].replace(mapping.SNAPSHOT, base)
        source['indexes'] = []
        source['retrievalAttempts'] = attempts
        source['dscRetrieved'] = pin
        source['dscSignatureVerified'] = False
        supplemental.append(source)
    dsc_checks = []
    for source in table['sources']:
        if source['package'] not in ('mesa', 'llvm-toolchain-20', 'base-files'):
            continue
        raw, pin = fetch(source['dsc']['url'], cache)
        if pin['sha256'] != source['dsc']['sha256'] or pin['bytes'] != source['dsc']['bytes']:
            raise ValueError('取得した.dscのSHA-256/sizeが索引と一致しません')
        fields = dsc_fields(raw)
        if fields['Source'] != source['package'] or fields['Version'] != source['version']:
            raise ValueError('取得した.dscのsource名/版が索引と一致しません')
        dsc_checks.append({'sourceId': source['id'], **pin, 'matchesIndex': True,
                           'signatureVerified': False})
    java = []
    for image_java in table['java']:
        release = dict(line.split('=', 1) for line in image_java['release'].splitlines() if '=' in line)
        release = {k: v.strip('"') for k, v in release.items()}
        major = release['SEMANTIC_VERSION'].split('.')[0]
        version = release['SEMANTIC_VERSION']
        tag = 'jdk8u{}-b{:02d}'.format(version.split('+')[0].split('.')[2], int(version.split('+')[1])) if major == '8' else 'jdk-' + version
        url = 'https://api.github.com/repos/adoptium/temurin' + major + '-binaries/releases/tags/' + urllib.parse.quote(tag, safe='')
        raw, pin = fetch(url, cache)
        metadata = json.loads(raw)
        if metadata['tag_name'] != tag:
            raise ValueError('Adoptium release tagが一致しません')
        assets = [a for a in metadata['assets'] if '-sources_' in a['name'] and a['name'].endswith('.tar.gz')]
        if len(assets) != 1 or not assets[0].get('digest', '').startswith('sha256:'):
            raise ValueError('Adoptium source assetのSHA-256がありません')
        asset = assets[0]
        source_repo = release['SOURCE_REPO'].removesuffix('.git')
        source_tag = tag + '_adopt'
        ref_url = source_repo.replace('https://github.com/', 'https://api.github.com/repos/') + '/git/ref/tags/' + urllib.parse.quote(source_tag, safe='')
        ref_raw, ref_pin = fetch(ref_url, cache)
        obj = json.loads(ref_raw)['object']
        pins = [pin, ref_pin]
        if obj['type'] == 'tag':
            tag_raw, tag_pin = fetch(obj['url'], cache)
            pins.append(tag_pin)
            obj = json.loads(tag_raw)['object']
        commit = obj['sha']
        image_commit = release['SOURCE'].split(':')[-1].rstrip('+')
        java.append({'home': image_java['home'], 'version': version, 'releaseTag': tag,
                     'releaseUrl': metadata['html_url'], 'sourceRepo': source_repo,
                     'sourceTag': source_tag, 'sourceCommit': commit,
                     'imageSource': release['SOURCE'], 'imageSourceCommitMatchesTag': commit.startswith(image_commit),
                     'imageSourceHasLocalChangesMarker': release['SOURCE'].endswith('+'),
                     'buildSource': release['BUILD_SOURCE'], 'buildSourceRepo': release['BUILD_SOURCE_REPO'],
                     'sourceArchive': {'url': asset['browser_download_url'], 'bytes': asset['size'],
                                       'sha256': asset['digest'].removeprefix('sha256:'),
                                       'bytesVerified': False},
                     'metadata': pins})
    return {'sources': supplemental, 'unresolved': unresolved, 'java': java, 'dscChecks': dsc_checks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--table', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = collect(json.loads(args.table.read_bytes()), args.cache)
    args.output.write_bytes(mapping.encode(result))
    print(json.dumps({'supplementalSources': len(result['sources']), 'unresolved': result['unresolved'],
                      'java': [{k: j[k] for k in ('home', 'releaseTag', 'imageSourceCommitMatchesTag')}
                               for j in result['java']]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
