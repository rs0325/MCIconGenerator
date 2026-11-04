#!/usr/bin/env python3
"""承認済みF1 worktreeで固定51版を描画し、専用distroと取得資産を回収する。"""
import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import urllib.request
import urllib.error
from urllib.parse import urlparse
import uuid
import winreg

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / 'build/f1'
WSL = 'C:/Windows/System32/wsl.exe'
ALLOWED = {'piston-meta.mojang.com', 'launchermeta.mojang.com', 'piston-data.mojang.com',
           'launcher.mojang.com', 'libraries.minecraft.net', 'resources.download.minecraft.net', 'maven.ornithemc.net'}


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


def registrations():
    result = []
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Lxss') as key:
        default = winreg.QueryValueEx(key, 'DefaultDistribution')[0]
        for i in range(winreg.QueryInfoKey(key)[0]):
            guid = winreg.EnumKey(key, i)
            with winreg.OpenKey(key, guid) as child:
                result.append({'guid': guid, 'name': winreg.QueryValueEx(child, 'DistributionName')[0],
                               'basePath': winreg.QueryValueEx(child, 'BasePath')[0],
                               'version': winreg.QueryValueEx(child, 'Version')[0]})
    return {'default': default, 'distributions': sorted(result, key=lambda row: row['guid'])}


class Acceptance:
    def __init__(self):
        self.run = ROOT / ('run-' + uuid.uuid4().hex[:12])
        self.run.mkdir(parents=True)
        self.name = 'mcicon-f1-' + uuid.uuid4().hex[:16]
        self.base = self.run / 'distro'
        self.cache = self.run / 'cache'
        self.logs = self.run / 'logs'
        self.logs.mkdir()
        self.lock = threading.Lock()
        self.download_lock = threading.Lock()
        self.stop = threading.Event()
        self.downloads = []
        self.commands = []
        self.registration = None
        self.import_attempted = False
        self.daemon = None
        self.result = {'distro': self.name, 'run': str(self.run), 'versions': [], 'errors': [], 'cleanup': {}}

    def command(self, label, args, timeout=60, data=None):
        start = time.monotonic()
        value = subprocess.run([str(v) for v in args], input=data, capture_output=True, timeout=timeout)
        out = value.stdout.decode('utf-16-le' if b'\0' in value.stdout else 'utf-8', errors='replace').lstrip('\ufeff')
        err = value.stderr.decode('utf-16-le' if b'\0' in value.stderr else 'utf-8', errors='replace').lstrip('\ufeff')
        (self.logs / (label + '.stdout')).write_bytes(value.stdout)
        (self.logs / (label + '.stderr')).write_bytes(value.stderr)
        record = {'name': label, 'argv': [str(v) for v in args], 'exitCode': value.returncode,
                  'seconds': round(time.monotonic() - start, 3), 'stdout': out, 'stderr': err}
        with self.lock:
            self.commands.append(record)
            save(self.run / 'commands.json', self.commands)
        return record

    def must(self, row):
        if row['exitCode']:
            raise RuntimeError(row['name'] + ': ' + row['stderr'][-1800:])
        return row['stdout']

    def guest(self, label, args, timeout=60, data=None):
        return self.command(label, [WSL, '-d', self.name, '-u', 'root', '--exec'] + args, timeout, data)

    def snapshot(self, label):
        return {k: self.must(self.command(label + '-' + k, [WSL] + args)) for k, args in
                [('list', ['--list', '--verbose']), ('status', ['--status'])]}

    def fetch(self, url, relative, sha1=None, size=None):
        if self.stop.is_set():
            raise AcquisitionStopped('取得不可または許可外入力のため停止済み')
        try:
            check_url(url)
        except AcquisitionStopped:
            self.stop.set()
            raise
        target = self.cache / relative
        # 同じassetを並列版から要求しても取得・確定は一度だけにする。
        with self.download_lock:
            if target.is_file():
                if sha1 and digest(target, 'sha1') != sha1:
                    raise RuntimeError('cache SHA-1不一致: ' + relative)
                return target
        try:
            with urllib.request.build_opener(OfficialOnly).open(url, timeout=120) as response:
                data = response.read()
        except (urllib.error.URLError, AcquisitionStopped) as error:
            self.stop.set()
            raise AcquisitionStopped('取得を停止: ' + url + ': ' + str(error)) from error
        actual = hashlib.sha1(data).hexdigest()
        if (sha1 and actual != sha1) or (size is not None and len(data) != size):
            self.stop.set()
            raise AcquisitionStopped('公式SHA-1/size不一致: ' + url)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.part')
        temporary.write_bytes(data)
        with self.download_lock:
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
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(obtain, requests))
        if version in self.pins:
            pin = self.pins[version]
            for kind, name in [('feather', 'feather.jar'), ('intermediary', 'intermediary.jar')]:
                source = ROOT / 'inputs/mappings' / version / name
                if digest(source, 'sha1') != pin[kind]:
                    raise RuntimeError('製品mappings pin不一致')
                target = self.cache / 'mappings' / version / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        major = meta.get('javaVersion', {}).get('majorVersion', 8 if version in self.pins else 21)
        return 17 if major == 16 else major

    def version(self, version):
        started = time.monotonic()
        row = {'id': version, 'runs': [], 'passed': False}
        try:
            if self.stop.is_set():
                raise AcquisitionStopped('取得停止に伴い未実行')
            print('PREPARE', version, flush=True)
            major = self.prepare(version)
            row['expectedJavaMajor'] = major
            for attempt in (1, 2):
                output = self.run / ('out-' + version + '-' + str(attempt))
                command = ['/opt/f1java/bin/java', '-Djava.awt.headless=true', '-cp', self.linux(REPO / 'cli/build/install/mcicon/lib') + '/*',
                           'io.github.rs0325.mcicon.cli.Main', '--debug', 'render', '--version', version,
                           '--id', 'minecraft:stone', '--size', '64', '--cache', self.linux(self.cache),
                           '--out', self.linux(output), '--offline', '--json', '--allow-experimental',
                           '--runtime', 'docker', '--docker-context', 'default', '--docker-image', self.image,
                           '--worker-jar', self.linux(REPO / 'cli/build/install/mcicon/worker/worker-agent.jar')]
                result = self.guest(version + '-' + str(attempt), command, timeout=600)
                record = {k: result[k] for k in ['exitCode', 'seconds', 'stderr']}
                record['pngs'] = []
                for png in output.rglob('*.png'):
                    width, height, pixels = self.audit.rgba_png(png.read_bytes())
                    record['pngs'].append({'sha256': digest(png), 'width': width, 'height': height,
                                          'visiblePixels': sum(a != 0 for a in pixels[3::4]), 'bytes': png.stat().st_size})
                manifest = output / 'java' / version / 'icons/manifest.json'
                record['settings'] = json.loads(manifest.read_text(encoding='utf-8')).get('settings', {}) if manifest.exists() else {}
                record['passed'] = result['exitCode'] == 0 and len(record['pngs']) == 1 and all(
                    p['width'] == p['height'] == 64 and p['visiblePixels'] > 0 for p in record['pngs']) and record['settings'].get('javaMajor') == major
                row['runs'].append(record)
            first = list((self.run / ('out-' + version + '-1')).rglob('*.png'))
            second = list((self.run / ('out-' + version + '-2')).rglob('*.png'))
            row['byteIdentical'] = len(first) == len(second) == 1 and first[0].read_bytes() == second[0].read_bytes()
            row['passed'] = all(r['passed'] for r in row['runs']) and row['byteIdentical']
        except Exception as error:
            row['error'] = str(error)
            if isinstance(error, AcquisitionStopped):
                self.stop.set()
        row['seconds'] = round(time.monotonic() - started, 3)
        with self.lock:
            self.result['versions'].append(row)
            save(self.run / 'result.json', self.result)
        print('RESULT', version, row['passed'], row.get('error', ''), row['seconds'], flush=True)
        return row

    @staticmethod
    def linux(path):
        return '/mnt/' + path.drive[0].lower() + path.as_posix()[2:]

    def execute(self):
        self.result['before'] = self.snapshot('before')
        self.original = registrations()
        self.result['originalRegistrations'] = self.original
        smoke_spec = importlib.util.spec_from_file_location('smoke', Path(__file__).with_name('smoke-candidate.py'))
        smoke = importlib.util.module_from_spec(smoke_spec)
        smoke_spec.loader.exec_module(smoke)
        audit_spec = importlib.util.spec_from_file_location('audit', REPO / 'scripts/audit-export.py')
        self.audit = importlib.util.module_from_spec(audit_spec)
        audit_spec.loader.exec_module(self.audit)
        inputs = json.loads((REPO / 'qa/windows-smoke-2026-11-04.json').read_text(encoding='utf-8'))['inputs'][:2]
        self.result['inputs'] = inputs
        for ref in inputs:
            path = Path(ref['path'])
            if path.stat().st_size != ref['bytes'] or digest(path) != ref['sha256']:
                raise RuntimeError('候補資材pin不一致')
        c1 = json.loads((REPO / 'qa/windows-rootfs-repro-2026-11-04.json').read_text(encoding='utf-8'))['runs'][0]
        tar = self.run / 'rootfs.tar'
        smoke.unpack(Path(inputs[0]['path']), tar, c1['tar_bytes'], c1['tar_sha256'])
        if any(r['name'].casefold() == self.name.casefold() for r in self.original['distributions']) or self.base.exists():
            raise RuntimeError('専用distro名/path衝突')
        self.import_attempted = True
        self.must(self.command('import', [WSL, '--import', self.name, self.base, tar, '--version', '2'], timeout=180))
        current = registrations()
        own = [r for r in current['distributions'] if r['name'] == self.name]
        if len(own) != 1 or Path(own[0]['basePath'].removeprefix('\\\\?\\')).resolve() != self.base.resolve():
            raise RuntimeError('専用distro所有不一致')
        self.registration = own[0]
        if current['default'] != self.original['default'] or [r for r in current['distributions'] if r['name'] != self.name] != self.original['distributions']:
            raise RuntimeError('既存登録変更')
        self.must(self.guest('mkdir', ['/bin/mkdir', '-p', '/var/lib/f1', '/run/f1']))
        daemon_args = [WSL, '-d', self.name, '-u', 'root', '--exec', '/usr/bin/dockerd', '--host', 'unix:///var/run/docker.sock',
                       '--data-root', '/var/lib/f1/docker', '--exec-root', '/run/f1/docker', '--pidfile', '/run/f1/dockerd.pid',
                       '--bridge', 'none', '--iptables=false', '--ip6tables=false', '--ip-forward=false', '--ip-masq=false', '--userland-proxy=false']
        with (self.logs / 'dockerd.stdout').open('wb') as out, (self.logs / 'dockerd.stderr').open('wb') as err:
            self.daemon = subprocess.Popen(daemon_args, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        self.result['daemonHostPid'] = self.daemon.pid
        for i in range(40):
            info = self.guest('docker-info-' + str(i), ['/usr/bin/docker', 'info', '--format', '{{json .}}'])
            if info['exitCode'] == 0:
                break
            if self.daemon.poll() is not None:
                raise RuntimeError('専用daemon起動失敗')
            time.sleep(1)
        value = json.loads(self.must(info))
        self.result['dockerInfo'] = value
        if not value['MemoryLimit'] or not value['CpuCfsQuota']:
            raise RuntimeError('container CPU/memory上限が使用不能')
        self.must(self.guest('docker-load', ['/usr/bin/docker', 'load', '-i', self.linux(Path(inputs[1]['path']))], timeout=180))
        self.image = json.loads((REPO / 'qa/windows-helper-repro-2026-11-04.json').read_text(encoding='utf-8'))['rendererRuns'][0]['imageId']
        inspected = json.loads(self.must(self.guest('image-inspect', ['/usr/bin/docker', 'image', 'inspect', self.image])))
        self.result['image'] = inspected
        self.must(self.guest('java-source', ['/usr/bin/docker', 'create', '--name', 'f1-java-source', '--network', 'none', self.image, 'true']))
        self.must(self.guest('java-copy', ['/usr/bin/docker', 'cp', 'f1-java-source:/opt/java/java21', '/opt/f1java']))
        self.must(self.guest('java-source-remove', ['/usr/bin/docker', 'rm', 'f1-java-source']))
        manifest_path = self.fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', 'metadata/version_manifest_v2.json')
        self.official = {r['id']: r for r in json.loads(manifest_path.read_bytes())['versions']}
        self.pins = json.loads((REPO / 'adapters/legacy-mappings.json').read_text())
        versions = [r['id'] for r in json.loads((REPO / 'adapters/releases.json').read_text())['versions']]
        pilot = self.version('1.21.4')
        if not pilot['passed']:
            raise RuntimeError('pilot不合格。全版実行前に診断が必要')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(self.version, [v for v in versions if v != '1.21.4']))

    def cleanup(self):
        if self.registration:
            current = registrations()
            if [r for r in current['distributions'] if r['name'] == self.name] != [self.registration]:
                raise RuntimeError('回収前の所有登録不一致')
            containers = self.guest('containers-after', ['/usr/bin/docker', 'ps', '-aq'])
            self.result['cleanup']['containersBeforeUnregister'] = containers
        if self.import_attempted:
            own = [r for r in registrations()['distributions'] if r['name'] == self.name]
            if own:
                if len(own) != 1 or Path(own[0]['basePath'].removeprefix('\\\\?\\')).resolve() != self.base.resolve():
                    raise RuntimeError('回収対象BasePath不一致')
                self.must(self.command('unregister', [WSL, '--unregister', self.name], timeout=120))
            if self.daemon:
                self.daemon.wait(timeout=30)
                self.result['cleanup']['daemonHostExit'] = self.daemon.returncode
            if self.base.exists():
                self.remove(self.base)
            self.result['cleanup']['registrationAbsent'] = not any(r['name'] == self.name for r in registrations()['distributions'])
            self.result['cleanup']['basePathAbsent'] = not self.base.exists()
        for directory in [self.cache] + list(self.run.glob('out-*')):
            if directory.exists():
                self.remove(directory)
        self.result['cleanup']['assetsRemoved'] = not self.cache.exists() and not list(self.run.glob('out-*'))
        self.result['after'] = self.snapshot('after')
        self.result['cleanup']['wslUnchanged'] = self.result['after'] == self.result['before'] and registrations() == self.original

    def remove(self, path):
        if path.resolve().parent != self.run.resolve() or path.is_symlink() or path.is_junction():
            raise RuntimeError('回収path境界不一致')
        shutil.rmtree(path)


def main():
    if os.name != 'nt' or REPO != Path('H:/MCIconGenerator/worktrees/milestone-render-win-f1/MCIconGenerator'):
        raise RuntimeError('承認済みF1 Windows worktreeだけで実行する')
    job = Acceptance()
    print('RUN', str(job.run), flush=True)
    try:
        job.execute()
    except Exception as error:
        job.result['errors'].append(str(error))
        print('ERROR', str(error), flush=True)
    finally:
        try:
            job.cleanup()
        except Exception as error:
            job.result['errors'].append('cleanup: ' + str(error))
        save(job.run / 'result.json', job.result)
        print('FINISHED', str(job.run), json.dumps(job.result['errors']), flush=True)
    return int(bool(job.result['errors']) or any(not row['passed'] for row in job.result['versions']))


if __name__ == '__main__':
    raise SystemExit(main())
