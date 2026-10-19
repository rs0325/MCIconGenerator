"""Mac host-helperの確定file manifestと公開先からinventory/descriptor artifact項目を生成する。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat

MAX_INVENTORY = 4 * 1024 * 1024

def invalid(message):
    raise ValueError(message)

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            invalid('JSON keyが重複しています')
        result[key] = value
    return result

def load(path):
    raw = path.read_bytes()
    return json.loads(raw, object_pairs_hook=unique_object), raw

def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode('utf-8')

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def sha(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value):
        invalid('SHA-256が不正です')
    return value

def positive(value):
    if type(value) is not int or not 0 < value < 2**63:
        invalid('sizeは正の整数が必要です')
    return value

def nonnegative(value):
    if type(value) is not int or not 0 <= value < 2**63:
        invalid('file sizeは非負の整数が必要です')
    return value

def text(value):
    if not isinstance(value, str) or not value:
        invalid('空でない文字列が必要です')
    return value

def identifier(value):
    if not re.fullmatch('[a-z0-9][a-z0-9._-]{0,95}', text(value)):
        invalid('IDが不正です')
    return value

def relative(value):
    value = text(value)
    if len(value) > 512 or not re.fullmatch('[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*', value) or any(x in ('.', '..') for x in value.split('/')):
        invalid('相対pathが不正です')
    return value

def https(value):
    # 同releaseの固定asset URLだけを受け付け、URIの一般形は扱わない。
    label = r'[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?'
    host = rf'(?:{label}\.)+[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?'
    if not isinstance(value, str) or len(value) > 8192 or not re.fullmatch(rf"https://{host}/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+", value) or re.search(r'%(?![0-9A-Fa-f]{2})', value):
        invalid('公開HTTPS URLが未確定または固定asset URL形式ではありません')
    return value

def paths(values):
    if not isinstance(values, list):
        invalid('path配列が必要です')
    result = [relative(value) for value in values]
    if len(result) != len(set(result)):
        invalid('pathが重複しています')
    return result

def regular(root, value):
    path = root
    for part in relative(value).split('/'):
        path = path / part
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode):
            invalid('license/noticeのlinkは使用できません')
    if not stat.S_ISREG(info.st_mode):
        invalid('license/noticeが通常fileではありません')
    return path

def generate(tree, manifest_path, component_path, publication_path):
    manifest, original = load(manifest_path)
    mapping, _ = load(component_path)
    publication, _ = load(publication_path)
    if type(manifest['schemaVersion']) is not int or manifest['schemaVersion'] != 1 or manifest['finalBytesConfirmed'] is not True:
        invalid('最終bytesのfile manifestが必要です')
    if manifest['host'] != {'os': 'macos', 'arch': 'aarch64'} or manifest['minimumOsVersion'] != '15.7.7':
        invalid('承認済みMac対応範囲と一致しません')
    if not re.fullmatch('[0-9a-f]{40}', text(manifest['sourceCommit'])):
        invalid('source commitが不正です')
    if digest(original) != sha(publication['fileManifestSha256']):
        invalid('file manifestのpinが一致しません')
    if not tree.is_dir() or tree.resolve() != tree.absolute():
        invalid('helper treeはlinkのない実directoryが必要です')
    artifact = manifest['artifact']
    if artifact['role'] != 'host-helper' or artifact['format'] != 'zip':
        invalid('Mac host-helper ZIPだけを生成できます')
    artifact_id = identifier(artifact['id'])
    version = text(artifact['version'])
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?', version):
        invalid('tool版が不正です')
    prefix = relative(manifest['archivePrefix'])
    inventory_path = relative(publication['inventoryPath'])
    artifact_url = https(publication['artifactUrl'])
    # 大binaryの再hashはpackagerの責務。ここでは固定manifestをpinし、小文書を実読取する。
    files = {}
    for row in manifest['files']:
        name = relative(row['path'])
        if name in files:
            invalid('file manifestのpathが重複しています')
        if not re.fullmatch('0o[0-7]{3}', text(row['mode'])):
            invalid('file modeが不正です')
        files[name] = {'path': relative(prefix + '/' + name), 'bytes': nonnegative(row['bytes']), 'sha256': sha(row['sha256']), 'mode': row['mode']}
    if not files:
        invalid('file manifestが空です')
    sources = {}
    source_members = {}
    if not publication['sources']:
        invalid('同梱JRE等のsource bundle参照が必要です')
    for row in publication['sources']:
        source_id = identifier(row['id'])
        if source_id in sources:
            invalid('source IDが重複しています')
        sources[source_id] = {'id': source_id, 'url': https(row['url']), 'bytes': positive(row['bytes']), 'sha256': sha(row['sha256']),
                              'manifestSha256': sha(row['manifestSha256'])}
        source_manifest, raw = load(Path(row['manifestPath']))
        if digest(raw) != row['manifestSha256']:
            invalid('source bundle manifestのpinが一致しません')
        members = {}
        for member in source_manifest['files']:
            name = text(member['relativePath'])
            if name.startswith('/') or '\\' in name or any(x in ('', '.', '..') for x in name.split('/')) or name in members:
                invalid('source bundle内pathが不正または重複しています')
            members[name] = (nonnegative(member['bytes']), sha(member['sha256']))
        source_members[source_id] = members
    if type(mapping['schemaVersion']) is not int or mapping['schemaVersion'] != 1 or not mapping['components']:
        invalid('component mappingが必要です')
    components = []
    ids = set()
    covered = set()
    licenses = set()
    notices = set()
    document_pins = {}
    used_sources = set()
    for item in mapping['components']:
        component_id = identifier(item['id'])
        if component_id in ids:
            invalid('component IDが重複しています')
        ids.add(component_id)
        selected = paths(item['files'])
        if not selected or any(name not in files for name in selected):
            invalid('componentに不在fileがあります')
        covered.update(selected)
        license_paths = paths(item['licenseFiles'])
        notice_paths = paths(item['noticeFiles'])
        if not license_paths:
            invalid('componentのlicenseが空です')
        for name in license_paths + notice_paths:
            if name not in files:
                invalid('license/noticeがfile manifestにありません')
            path = regular(tree, name)
            raw = path.read_bytes()
            if len(raw) != files[name]['bytes'] or digest(raw) != files[name]['sha256']:
                invalid('license/notice本文のsize/SHAが一致しません')
            document_pins[prefix + '/' + name] = files[name]
        licenses.update(prefix + '/' + name for name in license_paths)
        notices.update(prefix + '/' + name for name in notice_paths)
        selected_sources = item['sourceArchiveIds']
        if not isinstance(selected_sources, list) or len(set(selected_sources)) != len(selected_sources) or any(name not in sources for name in selected_sources):
            invalid('componentのsource参照が不正です')
        used_sources.update(selected_sources)
        external = []
        for entry in item['externalNotices']:
            source_id = entry['archiveId']
            name = entry['path']
            if source_id not in selected_sources or name not in source_members[source_id] or entry['kind'] not in ('license', 'notice'):
                invalid('外部license/noticeがsource bundleにありません')
            size, hashed = source_members[source_id][name]
            if hashed != sha(entry['sha256']):
                invalid('外部license/noticeのpinが一致しません')
            external.append({'archiveId': source_id, 'path': name, 'kind': entry['kind'], 'bytes': size, 'sha256': hashed})
        components.append({'id': component_id, 'name': text(item['name']), 'version': text(item['version']),
            'files': [prefix + '/' + name for name in selected],
            'licenseFiles': [prefix + '/' + name for name in license_paths],
            'noticeFiles': [prefix + '/' + name for name in notice_paths],
            'sourceArchiveIds': selected_sources, 'externalNotices': external})
    if covered != set(files):
        invalid('component未帰属のfileがあります')
    if used_sources != set(sources):
        invalid('source bundleの参照集合が一致しません')
    inventory = {'schemaVersion': 1, 'artifactId': artifact_id, 'role': 'host-helper', 'toolVersion': version,
        'host': manifest['host'], 'minimumOsVersion': manifest['minimumOsVersion'], 'sourceCommit': manifest['sourceCommit'],
        'artifact': {'url': artifact_url, 'bytes': positive(artifact['bytes']), 'sha256': sha(artifact['sha256']),
                     'unpackedBytes': positive(artifact['unpackedBytes']), 'format': 'zip'},
        'fileManifestSha256': digest(original), 'files': list(files.values()), 'components': components,
        'sourceArchives': list(sources.values())}
    raw = encode(inventory)
    if len(raw) > MAX_INVENTORY:
        invalid('inventoryが4MiBを超えています')
    for token in [b'/Users/', b'/private/', b'hostBinding', b'bootSessionId', b'privateKey']:
        if token in raw:
            invalid('inventoryにprivate metadataがあります')
    descriptor = {'id': artifact_id, 'role': 'host-helper', 'url': artifact_url,
        'bytes': inventory['artifact']['bytes'], 'sha256': inventory['artifact']['sha256'],
        'unpackedBytes': inventory['artifact']['unpackedBytes'], 'format': 'zip',
        'inventoryPath': inventory_path, 'inventorySha256': digest(raw),
        'licenseFiles': sorted(licenses), 'noticeFiles': sorted(notices),
        'sourceArchives': [{'url': source['url'], 'sha256': source['sha256']} for source in sources.values()]}
    return raw, encode(descriptor), {'status': 'PASS-final-input-inventory-generated', 'inventoryBytes': len(raw),
        'inventorySha256': digest(raw), 'files': len(files), 'components': len(components),
        'smallDocumentsVerified': len(document_pins), 'sourceBundles': len(sources),
        'binaryArchiveRehashed': False, 'signedOrPublished': False}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tree', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--components', type=Path, required=True)
    parser.add_argument('--publication', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        invalid('既存出力は上書きしません')
    inventory, fragment, report = generate(args.tree, args.manifest, args.components, args.publication)
    args.output.mkdir(mode=0o700)
    for name, raw in [('inventory.json', inventory), ('artifact-fragment.json', fragment), ('result.json', encode(report))]:
        with (args.output / name).open('xb') as stream:
            stream.write(raw)
    print(json.dumps(report, ensure_ascii=False))

if __name__ == '__main__':
    main()
