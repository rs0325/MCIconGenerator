#!/usr/bin/env python3
"""候補bytesだけで使い捨てWSLを検査する。製品署名・ゲーム受入は行わない。"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import lzma
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import zipfile

REPO = Path(__file__).resolve().parents[3]
WSL = Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32/wsl.exe'


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def decode(value):
    return value.decode('utf-16-le' if b'\0' in value else 'utf-8', errors='replace').lstrip('\ufeff')


def unpack(source, target, size, digest):
    decoder = lzma.LZMADecompressor(memlimit=64 * 1024 * 1024)
    count = 0
    with source.open('rb') as incoming, target.open('xb') as outgoing:
        while chunk := incoming.read(65536):
            data = decoder.decompress(chunk, max_length=65536)
            while True:
                count += len(data)
                if count > size:
                    raise RuntimeError('rootfs tar上限超過')
                outgoing.write(data)
                if decoder.eof or decoder.needs_input:
                    break
                data = decoder.decompress(b'', max_length=65536)
            if decoder.eof:
                if decoder.unused_data or incoming.read(1):
                    raise RuntimeError('XZの末尾データを拒否')
                break
    if not decoder.eof or count != size or sha(target) != digest:
        raise RuntimeError('rootfs tar pin不一致')


def same_base(value, path):
    return Path(value.removeprefix('\\\\?\\')).resolve() == path.resolve()


def process_started():
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    values = [wintypes.FILETIME() for _ in range(4)]
    if not kernel.GetProcessTimes(kernel.GetCurrentProcess(), *(ctypes.byref(v) for v in values)):
        raise ctypes.WinError(ctypes.get_last_error())
    return (((values[0].dwHighDateTime << 32) | values[0].dwLowDateTime) - 116444736000000000) // 10000


class Smoke:
    def __init__(self, args):
        self.args = args
        self.run = REPO / 'build/e2' / str(uuid.uuid4())
        self.run.mkdir(parents=True)
        if self.run.resolve() != self.run or self.run.parent.resolve() != REPO / 'build/e2':
            raise RuntimeError('専用出力pathのlinkを拒否')
        self.name = 'MCIcon-e2e2e2' + uuid.uuid4().hex[:18]
        self.base = self.run / 'distro'
        self.native = self.run / 'windows-host.exe'
        self.started = process_started()
        self.records = []
        self.jobs = []
        self.keeper = None
        self.import_attempted = False
        self.registration = None
        self.result = {'schemaVersion': 1, 'refs': [91, 95], 'distro': self.name,
                       'basePath': str(self.base), 'checks': {}, 'errors': [], 'cleanup': {}}

    def command(self, name, argv, data=None, timeout=45, native=False, bridge=False):
        prefix = self.run / ('native-' + str(uuid.uuid4()))
        actual = [str(v) for v in argv]
        if native:
            mode = 'exec-bridge' if bridge else 'exec'
            command = [str(self.native), mode, str(os.getpid()), str(self.started), str(prefix), '--']
            if bridge:
                command += [str(self.keeper_client['clientPid']), str(self.keeper_client['clientStarted']), '--']
            actual = command + actual
        begin = time.monotonic()
        child = subprocess.Popen(actual, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if native:
            self.jobs.append((prefix, child.pid, None))
        try:
            out, err = child.communicate(data, timeout=timeout)
        except subprocess.TimeoutExpired:
            child.terminate()
            out, err = child.communicate(timeout=10)
            self.result['errors'].append(name + ': timeout、native Job終端は別記録で判定')
        (self.run / (name + '.stdout')).write_bytes(out)
        (self.run / (name + '.stderr')).write_bytes(err)
        record = {'name': name, 'argv': actual, 'exitCode': child.returncode,
                  'seconds': round(time.monotonic() - begin, 3), 'stdout': decode(out), 'stderr': decode(err)}
        self.records.append(record)
        if native:
            self.jobs[-1] = (prefix, child.pid, child.returncode)
        save(self.run / 'commands.json', self.records)
        return record

    def must(self, record):
        if record['exitCode'] != 0:
            raise RuntimeError(record['name'] + ': exit ' + str(record['exitCode']))
        return record['stdout']

    def facts(self, name):
        return json.loads(self.must(self.command(name, [self.native, 'facts'])))

    def wsl(self, name, args, data=None, timeout=45, bridge=False):
        return self.command(name, [WSL, '--distribution', self.name, '--user', 'root', '--exec'] + args,
                            data=data, timeout=timeout, native=True, bridge=bridge)

    def bridge(self, operation, payload=None, **fields):
        request = dict(self.request, operation=operation, **fields)
        data = (json.dumps(request) + '\n').encode()
        if payload is not None:
            data += payload.read_bytes()
        return self.wsl(operation, ['/usr/bin/python3', '/usr/lib/mcicon/guestbridge.py'],
                        data, timeout=180 if payload else 60, bridge=self.keeper is not None)

    def snapshot(self, label):
        rows = self.command(label + '-list', [WSL, '--list', '--verbose'])
        status = self.command(label + '-status', [WSL, '--status'])
        self.must(rows)
        self.must(status)
        return {'list': rows['stdout'], 'status': status['stdout']}

    def execute(self):
        self.before = self.snapshot('before')
        self.result['before'] = self.before
        self.must(self.command('version', [WSL, '--version']))
        c1 = json.loads((REPO / 'qa/windows-rootfs-repro-2026-11-04.json').read_text(encoding='utf-8'))['runs'][0]
        e1 = json.loads((REPO / 'qa/windows-helper-repro-2026-11-04.json').read_text(encoding='utf-8'))
        pins = [(self.args.rootfs, c1['tar_xz_bytes'], c1['tar_xz_sha256']),
                (self.args.renderer, e1['rendererRuns'][0]['tarGz']['bytes'], e1['rendererRuns'][0]['tarGz']['sha256']),
                (self.args.helper, e1['finalAppImageRuns'][0]['bytes'], e1['finalAppImageRuns'][0]['sha256'])]
        self.result['inputs'] = []
        for path, size, digest in pins:
            observed = {'path': str(path), 'bytes': path.stat().st_size, 'sha256': sha(path)}
            self.result['inputs'].append(observed)
            if observed['bytes'] != size or observed['sha256'] != digest:
                raise RuntimeError('候補archive pin不一致: ' + str(path))
        with zipfile.ZipFile(self.args.helper) as archive:
            self.native.write_bytes(archive.read('MCIconHelper/app/support/windows-host.exe'))
        if sha(self.native) != e1['finalAppImageRuns'][0]['native']['sha256']:
            raise RuntimeError('native PE pin不一致')
        before = self.facts('facts-before')
        self.result['host'] = before
        if not before['defaultGuid'] or not before['distributions'] or before['memoryBytes'] < 8 * 1024**3:
            raise RuntimeError('既存defaultまたはhost memory不足')
        if shutil.disk_usage(self.run).free < 8 * 1024**3:
            raise RuntimeError('host空き容量不足')
        if any(row['name'].casefold() == self.name.casefold() for row in before['distributions']) or self.base.exists():
            raise RuntimeError('専用名/pathの衝突')
        marker = {'abi': 1, 'binding': {'sid': before['sid'], 'root': str(self.run),
                  'hostId': str(uuid.uuid4()), 'descriptorSha256': sha(self.args.helper)},
                  'generationOwner': str(uuid.uuid4())}
        self.request = dict(c1['inventory']['guestBase'], abi=1, marker=marker, memoryBytes=8 * 1024**3,
                            jobId=str(uuid.uuid4()), ownerId=str(uuid.uuid4()),
                            descriptorSha256=marker['binding']['descriptorSha256'])
        save(self.run / 'owner.json', {'state': 'reserved', 'marker': marker, 'originalDefault': before['defaultGuid']})
        tar = self.run / 'rootfs.tar'
        unpack(self.args.rootfs, tar, c1['tar_bytes'], c1['tar_sha256'])
        self.import_attempted = True
        self.must(self.command('import', [WSL, '--import', self.name, self.base, tar, '--version', '2'],
                               timeout=180, native=True))
        current = self.facts('facts-imported')
        rows = [row for row in current['distributions'] if row['name'] == self.name]
        if len(rows) != 1 or rows[0]['version'] != 2 or not same_base(rows[0]['basePath'], self.base):
            raise RuntimeError('新規登録の所有不一致')
        self.registration = rows[0]
        if current['defaultGuid'] != before['defaultGuid'] or [r for r in current['distributions'] if r['name'] != self.name] != before['distributions']:
            raise RuntimeError('既存登録/defaultが変化したため停止')
        save(self.run / 'owner.json', {'state': 'registered', 'marker': marker, 'registration': self.registration,
                                     'originalDefault': before['defaultGuid']})
        self.result['registration'] = self.registration
        diagnostic = '''import os,json,pathlib,subprocess
p=pathlib.Path
files=['/proc/meminfo','/proc/self/cgroup','/proc/1/cgroup','/proc/self/mountinfo','/sys/fs/cgroup/cgroup.controllers','/sys/fs/cgroup/cgroup.subtree_control','/proc/1/comm','/etc/wsl.conf']
result={'cpus':os.cpu_count(),'files':{f:p(f).read_text() if p(f).exists() else None for f in files},'versions':{}}
for f in ['/usr/bin/python3','/usr/bin/docker','/usr/bin/dockerd','/usr/bin/containerd','/usr/bin/runc']:
 r=subprocess.run([f,'--version'],capture_output=True,text=True,timeout=10);result['versions'][f]={'exitCode':r.returncode,'stdout':r.stdout,'stderr':r.stderr}
result['aptLogs']={str(f):f.stat().st_size for d in ['/var/log/apt','/var/log'] for f in p(d).glob('*') if f.is_file() and ('apt' in str(f) or f.name=='dpkg.log')}
result['processes']=[(f/'comm').read_text().strip() for f in p('/proc').iterdir() if f.name.isdigit() and (f/'comm').exists()]
print(json.dumps(result))
'''
        observed = json.loads(self.must(self.wsl('diagnostic', ['/usr/bin/python3', '-c', diagnostic])))
        self.result['guest'] = observed
        self.result['checks']['c'] = {'passed': all(v['exitCode'] == 0 for v in observed['versions'].values())}
        init = self.bridge('init')
        marker_code = 'import pathlib;print(pathlib.Path(' + repr('/var/lib/mcicon/owner.json') + ').read_text())'
        marker_read = self.wsl('marker', ['/usr/bin/python3', '-c', marker_code])
        marker_ok = marker_read['exitCode'] == 0 and json.loads(marker_read['stdout']) == marker
        self.result['checks']['a'] = {'passed': marker_ok, 'imported': True, 'registryAndDefaultVerified': True}
        self.result['checks']['b'] = {'passed': False, 'initExit': init['exitCode']}
        if init['exitCode'] != 0:
            raise RuntimeError('guestbridge initが候補を拒否。診断を保持し、daemon生成へ進まない')
        hello = json.loads(self.must(self.bridge('hello')))
        if hello['abi'] != 1 or hello['marker'] != marker:
            raise RuntimeError('ABI/hello marker不一致')
        self.result['checks']['b'] = {'passed': True, 'hello': hello}
        attempt = str(uuid.uuid4())
        prefix = self.run / ('native-' + str(uuid.uuid4()))
        out = (self.run / 'keeper.stdout').open('wb')
        err = (self.run / 'keeper.stderr').open('wb')
        argv = [str(self.native), 'exec-keeper', str(os.getpid()), str(self.started), str(prefix), '--', str(os.getpid()), str(self.started), '--', str(WSL),
                '--distribution', self.name, '--user', 'root', '--exec', '/usr/bin/python3', '/usr/lib/mcicon/guestbridge.py']
        self.keeper = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=out, stderr=err)
        out.close(); err.close()
        self.keeper_prefix = prefix
        self.keeper.stdin.write((json.dumps(dict(self.request, operation='keep', keeperAttempt=attempt)) + '\n').encode())
        self.keeper.stdin.flush()
        end = time.monotonic() + 20
        while not (self.run / 'keeper.stdout').stat().st_size:
            if self.keeper.poll() is not None or time.monotonic() >= end:
                raise RuntimeError('keeper起動失敗')
            time.sleep(.1)
        keeper = json.loads((self.run / 'keeper.stdout').read_text(encoding='utf-8'))
        self.result['keeper'] = keeper
        self.keeper_client = json.loads(Path(str(prefix) + '.client.json').read_text(encoding='utf-8'))
        claim = {'stop': None, 'identity': {k:self.request[k] for k in ['jobId','ownerId','descriptorSha256']}, 'keeperAttempt': attempt}
        daemon = json.loads(self.must(self.bridge('daemon-prepare', claim=claim)))
        self.result['checks']['d'] = {'passed': daemon['ready'], 'proof': daemon}
        docker = ['/usr/bin/docker', '--host', 'unix:///run/mcicon/docker.sock']
        self.result['dockerInfo'] = json.loads(self.must(self.wsl('docker-info', docker + ['info', '--format', '{{json .}}'])))
        image = e1['rendererRuns'][0]['imageId']
        loaded = self.bridge('load', payload=self.args.renderer, imageId=image, timeoutSeconds=120)
        actual = json.loads(self.must(loaded))
        self.result['image'] = actual
        if actual['Id'] != image or actual['Os'] != 'linux' or actual['Architecture'] != 'amd64':
            raise RuntimeError('load後のimage ID/platform不一致')
        smoke = 'set -eu; glxinfo -B; grep -m1 -w sse4_1 /proc/cpuinfo; /mcicon-cpu; for j in /opt/java/java8 /opt/java/java17 /opt/java/java21 /opt/java/openjdk; do $j/bin/java -version; done'
        args = ['create', '--name', 'mcicon-doctor-' + self.request['jobId'], '--network=none', '--pull=never',
                '--mount', 'type=bind,src=/usr/lib/mcicon/cpu-probe,dst=/mcicon-cpu,readonly']
        for key in ['jobId', 'ownerId', 'descriptorSha256']:
            args += ['--label', 'io.github.rs0325.mcicon.' + key + '=' + self.request[key]]
        args += ['--label', 'io.github.rs0325.mcicon.generation=' + marker['generationOwner'], image, 'sh', '-c', smoke]
        created = json.loads(self.must(self.bridge('create', args=args)))
        probe = self.bridge('start', id=created['id'], interactive=False)
        self.result['checks']['e'] = {'passed': probe['exitCode'] == 0 and 'llvmpipe' in probe['stdout'], 'loadPassed': True}
        self.must(probe)
        after = json.loads(self.must(self.wsl('diagnostic-after', ['/usr/bin/python3', '-c', diagnostic])))
        self.result['checks']['f'] = {'passed': None, 'aptLogsBefore': observed['aptLogs'], 'aptLogsAfter': after['aptLogs'],
                                     'reason': 'container network=none、daemon network無効。guest全体の通信遮断・offline製品経路は未確認'}
        release = json.loads(self.must(self.bridge('release')))
        self.result['guestRelease'] = release
        if not release['closed'] or release['actors'] or release['containers']:
            raise RuntimeError('guest release終端不一致')

    def cleanup(self):
        if self.keeper is not None:
            self.keeper.stdin.close()
            try:
                self.keeper.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.keeper.terminate(); self.keeper.wait(timeout=10)
            self.jobs.append((self.keeper_prefix, self.keeper.pid, self.keeper.returncode))
        if self.import_attempted:
            current = self.facts('facts-cleanup')
            rows = [row for row in current['distributions'] if row['name'] == self.name]
            if rows:
                if len(rows) != 1 or rows[0]['version'] != 2 or not same_base(rows[0]['basePath'], self.base) or (self.registration and rows[0] != self.registration):
                    raise RuntimeError('cleanup所有不一致。登録・diskを保持')
                self.must(self.command('unregister', [WSL, '--unregister', self.name], timeout=60, native=True))
            final = self.facts('facts-after')
            if any(row['name'] == self.name for row in final['distributions']):
                raise RuntimeError('専用登録が残存')
            self.result['cleanup']['registrationAbsent'] = True
            if self.base.exists():
                if self.base.resolve() != self.run / 'distro' or self.base.is_symlink() or self.base.is_junction():
                    raise RuntimeError('cleanup BasePath境界不一致')
                shutil.rmtree(self.base)
            self.result['cleanup']['basePathAbsent'] = not self.base.exists()
        receipts = []
        for prefix, pid, code in self.jobs:
            state = Path(str(prefix) + '.state.json')
            done = Path(str(prefix) + '.done.json')
            value = json.loads(state.read_text()) if state.exists() else None
            terminal = bool(value and done.exists() and json.loads(done.read_text()) == value and
                            value['pid'] == pid and value['parent'] == os.getpid() and value['parentStarted'] == self.started)
            receipts.append({'prefix': str(prefix), 'pid': pid, 'exitCode': code, 'jobEmptyReceipt': terminal})
        self.result['cleanup']['nativeJobs'] = receipts
        after = self.snapshot('after')
        self.result['after'] = after
        self.result['cleanup']['existingWslUnchanged'] = after == self.before
        self.result['checks']['g'] = {'passed': after == self.before and all(r['jobEmptyReceipt'] for r in receipts)
                                     and self.result['cleanup'].get('registrationAbsent', not self.import_attempted)
                                     and self.result['cleanup'].get('basePathAbsent', not self.import_attempted)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', type=Path, required=True)
    parser.add_argument('--renderer', type=Path, required=True)
    parser.add_argument('--helper', type=Path, required=True)
    args = parser.parse_args()
    if os.name != 'nt' or REPO != Path('H:/MCIconGenerator/worktrees/milestone-windows-smoke-e2/MCIconGenerator'):
        parser.error('承認済みWindows専用worktreeだけで実行する')
    for name in ['rootfs', 'renderer', 'helper']:
        setattr(args, name, getattr(args, name).resolve(strict=True))
    smoke = Smoke(args)
    try:
        smoke.execute()
    except Exception as failure:
        smoke.result['errors'].append(str(failure))
    finally:
        try:
            smoke.cleanup()
        except Exception as failure:
            smoke.result['errors'].append('cleanup: ' + str(failure))
        for key in 'abcdefg':
            smoke.result['checks'].setdefault(key, {'passed': None, 'reason': '前段失敗のため未検証'})
        save(smoke.run / 'result.json', smoke.result)
        print(json.dumps({'run': str(smoke.run), 'checks': smoke.result['checks'], 'errors': smoke.result['errors']}, ensure_ascii=False))
    return 1 if smoke.result['errors'] or any(value['passed'] is False for value in smoke.result['checks'].values()) else 0


if __name__ == '__main__':
    raise SystemExit(main())
