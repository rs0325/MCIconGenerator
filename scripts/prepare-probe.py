#!/usr/bin/env python3
"""Download verified official inputs for the rendering spike (never commit inputs)."""
import argparse
import concurrent.futures
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / '.local' / 'probe'


def get(url, dest, sha1=None, size=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and (sha1 is None or hashlib.sha1(dest.read_bytes()).hexdigest() == sha1):
        return dest
    # curl uses the host trust store, including managed enterprise certificates.
    data = subprocess.run(['curl', '--fail', '--silent', '--show-error',
                           '--location', '--max-time', '120', '--retry', '2', url],
                          check=True, capture_output=True).stdout
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError('SHA-1 mismatch: ' + url)
    if size is not None and len(data) != size:
        raise ValueError('Size mismatch: ' + url)
    temp = dest.with_suffix(dest.suffix + '.part')
    temp.write_bytes(data)
    temp.replace(dest)
    return dest


def allowed(rules, os_name, arch):
    result = not rules
    for rule in rules:
        os_rule = rule.get('os', {})
        if 'name' in os_rule and os_rule['name'] != os_name:
            continue
        if 'arch' in os_rule and not re.fullmatch(os_rule['arch'], arch):
            continue
        if rule.get('features'):
            continue
        result = rule['action'] == 'allow'
    return result


def prepare(version, os_name):
    manifest = json.loads(get('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', ROOT/'manifest.json').read_text())
    ref = next(v for v in manifest['versions'] if v['id'] == version)
    base = ROOT/version
    meta = json.loads(get(ref['url'], base/'version.json', ref['sha1']).read_text())
    jobs = []
    def schedule(entry, destination):
        jobs.append((entry['url'], destination, entry.get('sha1'), entry.get('size')))
    schedule(meta['downloads']['client'], base/'client.jar')
    if 'client_mappings' in meta['downloads']:
        schedule(meta['downloads']['client_mappings'], base/'mappings.txt')
    paths, native_paths = [], []
    for lib in meta['libraries']:
        if not allowed(lib.get('rules', []), os_name, 'amd64' if os_name == 'linux' else 'aarch64'):
            continue
        downloads = lib.get('downloads', {})
        if 'artifact' in downloads:
            entry = downloads['artifact']
            dest = ROOT/'libraries'/entry['path']
            paths.append(str(dest.relative_to(ROOT)))
            schedule(entry, dest)
        classifier = lib.get('natives', {}).get(os_name)
        if classifier:
            classifier = classifier.replace('${arch}', '64')
            entry = downloads['classifiers'][classifier]
            dest = ROOT/'libraries'/entry['path']
            native_paths.append(str(dest.relative_to(ROOT)))
            schedule(entry, dest)
    index_ref = meta['assetIndex']
    index_file = get(index_ref['url'], ROOT/'assets'/'indexes'/(index_ref['id']+'.json'), index_ref['sha1'])
    index = json.loads(index_file.read_text())
    for name, obj in index['objects'].items():
        # A rendering probe does not need hundreds of MB of audio samples.
        if name.endswith('.ogg'):
            continue
        h = obj['hash']
        schedule({'url': 'https://resources.download.minecraft.net/'+h[:2]+'/'+h, 'sha1': h, 'size': obj['size']}, ROOT/'assets'/'objects'/h[:2]/h)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda job: get(*job), jobs))
    natives = base/('natives-'+os_name)
    natives.mkdir(parents=True, exist_ok=True)
    for native in native_paths:
        with zipfile.ZipFile(ROOT/native) as archive:
            for info in archive.infolist():
                if info.is_dir() or info.filename.startswith('META-INF/'):
                    continue
                # Native libraries are flat; never trust archive paths.
                (natives/Path(info.filename).name).write_bytes(archive.read(info))
    (base/('classpath-'+os_name+'.json')).write_text(json.dumps(paths))
    print(version, os_name, 'prepared', len(jobs), 'verified inputs', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('versions', nargs='+')
    parser.add_argument('--os', default='linux', choices=['linux', 'osx'])
    args = parser.parse_args()
    for version in args.versions:
        prepare(version, args.os)
