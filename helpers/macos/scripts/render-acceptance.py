#!/usr/bin/env python3
"""F2の専用Mac領域で公式資産を取得し、固定51版を逐次2回描画する。"""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import urllib.error
from urllib.parse import urlparse
import uuid

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO.parent
ALLOWED = {'piston-meta.mojang.com', 'launchermeta.mojang.com', 'piston-data.mojang.com',
           'launcher.mojang.com', 'libraries.minecraft.net', 'resources.download.minecraft.net',
           'maven.ornithemc.net'}


class AcquisitionStopped(RuntimeError):
    pass


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path, algorithm='sha256'):
    with path.open('rb') as source:
        return hashlib.file_digest(source, algorithm).hexdigest()


def check_url(url):
    ref = urlparse(url)
    if ref.scheme != 'https' or ref.hostname not in ALLOWED:
        raise AcquisitionStopped('許可外URL: ' + url)


class OfficialOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Acceptance:
    def __init__(self):
        self.run = ROOT / 'run'
        self.cache = self.run / 'cache'
        self.logs = self.run / 'logs'
        self.logs.mkdir(parents=True, exist_ok=True)
        self.downloads = []
        self.result = {'versions': [], 'errors': []}
        self.image = 'sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc'

    def fetch(self, url, relative, sha1=None, size=None):
        check_url(url)
        target = self.cache / relative
        if target.is_file():
            if sha1 and digest(target, 'sha1') != sha1:
                raise RuntimeError('cache SHA-1不一致: ' + relative)
            return target
        try:
            with urllib.request.build_opener(OfficialOnly).open(url, timeout=120) as response:
                data = response.read()
        except (urllib.error.URLError, AcquisitionStopped) as error:
            raise AcquisitionStopped('取得を停止: ' + url + ': ' + str(error)) from error
        actual = hashlib.sha1(data).hexdigest()
        if (sha1 and actual != sha1) or (size is not None and len(data) != size):
            raise AcquisitionStopped('公式SHA-1/size不一致: ' + url)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.part')
        temporary.write_bytes(data)
        os.replace(temporary, target)
        target.with_name(target.name + '.sha256').write_text(hashlib.sha256(data).hexdigest())
        self.downloads.append({'url': url, 'path': relative, 'bytes': len(data), 'sha1': actual,
                               'sha256': hashlib.sha256(data).hexdigest(), 'officialSha1Matched': bool(sha1)})
        save(self.run / 'downloads.json', self.downloads)
        return target

    def prepare(self, version):
        ref = self.official[version]
        meta = json.loads(self.fetch(ref['url'], 'versions/' + version + '/version.json', ref['sha1']).read_bytes())
        requests = []
        for kind, name in [('client', 'client.jar'), ('client_mappings', 'mappings.txt')]:
            if kind in meta['downloads']:
                requests.append((meta['downloads'][kind], 'versions/' + version + '/' + name))
        for lib in meta['libraries']:
            allowed = not lib.get('rules')
            for rule in lib.get('rules', []):
                target = rule.get('os', {})
                if target.get('name', 'linux') != 'linux' or not re.fullmatch(target.get('arch', 'amd64'), 'amd64') or 'features' in rule:
                    continue
                allowed = rule['action'] == 'allow'
            if not allowed:
                continue
            downloads = lib['downloads']
            if 'artifact' in downloads:
                item = downloads['artifact']
                requests.append((item, 'libraries/' + item['path']))
            if 'linux' in lib.get('natives', {}):
                classifier = lib['natives']['linux'].replace('${arch}', '64')
                item = downloads['classifiers'][classifier]
                requests.append((item, 'libraries/' + item['path']))
        index = meta['assetIndex']
        objects = json.loads(self.fetch(index['url'], 'assets/indexes/' + index['id'] + '.json', index['sha1'], index['size']).read_bytes())['objects']
        for name, obj in objects.items():
            if not name.endswith('.ogg'):
                sha = obj['hash']
                requests.append(({'url': 'https://resources.download.minecraft.net/' + sha[:2] + '/' + sha,
                                  'sha1': sha, 'size': obj['size']}, 'assets/objects/' + sha[:2] + '/' + sha))
        def obtain(row):
            ref, path = row
            self.fetch(ref['url'], path, ref['sha1'], ref.get('size'))
        for request in requests:
            obtain(request)
        if version in self.pins:
            pin = self.pins[version]
            build = pin.get('featherBuild', 31)
            base = 'https://maven.ornithemc.net/releases/net/ornithemc/'
            refs = [
                ('feather', f'feather/{version}+build.{build}/feather-{version}+build.{build}-v2.jar', 'feather.jar'),
                ('intermediary', f'calamus-intermediary/{version}/calamus-intermediary-{version}-v2.jar', 'intermediary.jar'),
            ]
            for kind, url, name in refs:
                self.fetch(base + url, f'mappings/{version}/{name}', pin[kind])
        major = meta.get('javaVersion', {}).get('majorVersion', 8 if version in self.pins else 21)
        return 17 if major == 16 else major


    def command(self, label, args, timeout=600):
        start = time.monotonic()
        result = subprocess.run([str(a) for a in args], env=self.env, capture_output=True, timeout=timeout)
        (self.logs / (label + '.stdout')).write_bytes(result.stdout)
        (self.logs / (label + '.stderr')).write_bytes(result.stderr)
        return {'exitCode': result.returncode, 'seconds': round(time.monotonic() - start, 3),
                'stderrSummary': result.stderr.decode('utf-8', 'backslashreplace')[-4000:]}

    def version(self, version):
        started = time.monotonic()
        row = {'id': version, 'runs': [], 'passed': False}
        print('PREPARE', version, flush=True)
        try:
            major = self.prepare(version)
            row['expectedJavaMajor'] = major
            files = []
            for attempt in (1, 2):
                output = self.run / ('out-' + version + '-' + str(attempt))
                args = [self.java, '-Djava.awt.headless=true', '-cp', str(REPO / 'cli/build/install/mcicon/lib/*'),
                        'io.github.rs0325.mcicon.cli.Main', '--debug', 'render', '--version', version,
                        '--id', 'minecraft:stone', '--size', '64', '--cache', self.cache, '--out', output,
                        '--offline', '--json', '--allow-experimental', '--runtime', 'docker',
                        '--docker-context', 'default', '--docker-image', self.image,
                        '--worker-jar', REPO / 'cli/build/install/mcicon/worker/worker-agent.jar']
                record = self.command(version + '-' + str(attempt), args)
                pngs = sorted(output.rglob('*.png'))
                files.append(pngs)
                record['pngs'] = []
                for png in pngs:
                    width, height, pixels = self.audit.rgba_png(png.read_bytes())
                    record['pngs'].append({'sha256': digest(png), 'width': width, 'height': height,
                                          'visiblePixels': sum(a != 0 for a in pixels[3::4])})
                manifest = output / 'java' / version / 'icons/manifest.json'
                record['settings'] = json.loads(manifest.read_bytes()).get('settings', {}) if manifest.exists() else {}
                record['passed'] = (record['exitCode'] == 0 and len(record['pngs']) == 1
                                    and all(p['width'] == p['height'] == 64 and p['visiblePixels'] > 0
                                            for p in record['pngs'])
                                    and record['settings'].get('javaMajor') == major)
                row['runs'].append(record)
            row['byteIdentical'] = (len(files[0]) == len(files[1]) == 1
                                    and files[0][0].read_bytes() == files[1][0].read_bytes())
            row['sameInputSettings'] = row['runs'][0]['settings'] == row['runs'][1]['settings']
            row['passed'] = all(r['passed'] for r in row['runs']) and row['byteIdentical'] and row['sameInputSettings']
        except Exception as error:
            row['error'] = str(error)
            if isinstance(error, AcquisitionStopped):
                self.result['errors'].append(str(error))
                raise
        finally:
            row['seconds'] = round(time.monotonic() - started, 3)
            self.result['versions'].append(row)
            save(self.run / 'result.json', self.result)
            print('RESULT', version, row['passed'], row.get('error', ''), row['seconds'], flush=True)
        return row

    def execute(self):
        spec = importlib.util.spec_from_file_location('audit', REPO / 'scripts/audit-export.py')
        self.audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.audit)
        jdk = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
        self.java = Path(jdk) / 'bin/java'
        self.env = dict(os.environ, HOME=str(ROOT / 'home'), TMPDIR=str(ROOT / 'tmp') + '/',
                        LIMA_HOME=str(ROOT / 'l'), PATH=str(ROOT / 'qa-bin') + ':' + os.environ['PATH'])
        manifest = self.fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',
                              'metadata/version_manifest_v2.json')
        self.official = {r['id']: r for r in json.loads(manifest.read_bytes())['versions']}
        self.pins = json.loads((REPO / 'adapters/legacy-mappings.json').read_bytes())
        versions = [r['id'] for r in json.loads((REPO / 'adapters/releases.json').read_bytes())['versions']]
        if len(versions) != 51 or len(set(versions)) != 51:
            raise RuntimeError('固定51版の集合不一致')
        if not self.version('1.21.4')['passed']:
            raise RuntimeError('pilot不合格。全版実行を停止')
        for version in versions:
            if version != '1.21.4':
                self.version(version)


def main():
    if os.uname().sysname != 'Darwin' or ROOT != Path('/Users/ryuman/tmp-mcicon-f2'):
        raise RuntimeError('承認済みF2 Mac専用領域だけで実行する')
    job = Acceptance()
    if (job.run / 'result.json').exists():
        raise RuntimeError('既存の実行結果を上書きしない')
    try:
        job.execute()
    except Exception as error:
        job.result['errors'].append(str(error))
    finally:
        save(job.run / 'result.json', job.result)
        print('FINISHED', json.dumps(job.result['errors'], ensure_ascii=False), flush=True)
    return int(bool(job.result['errors']) or len(job.result['versions']) != 51
               or any(not row['passed'] for row in job.result['versions']))


if __name__ == '__main__':
    raise SystemExit(main())
