#!/usr/bin/python3
"""prepared rootfs ABI 1。固定daemonとjob fence以外のguest資源を操作しない。"""
import os, sys, json, hashlib, fcntl, subprocess, time, tarfile, pathlib, uuid, stat, re
ROOT = pathlib.Path('/var/lib/mcicon')
CGROUP = pathlib.Path('/sys/fs/cgroup/mcicon')
BOOT = pathlib.Path('/proc/sys/kernel/random/boot_id')
PROC = pathlib.Path('/proc')
SOCKET = '/run/mcicon/docker.sock'
DOCKER = ['/usr/bin/docker', '--host', 'unix://' + SOCKET]
OWNER = ROOT / 'owner.json'

def require(ok, detail):
    if not ok: raise RuntimeError(detail)

def read(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1048576, '所有record不明')
    with path.open() as source: return json.load(source)

def write(path, value):
    temporary = path.with_name(path.name + '.' + str(uuid.uuid4()) + '.part')
    try:
        with temporary.open('x') as target:
            json.dump(value, target, ensure_ascii=False, separators=(',', ':')); target.flush(); os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists(): temporary.unlink()

def digest(path):
    value = hashlib.sha256()
    with open(path, 'rb') as source:
        while chunk := source.read(65536): value.update(chunk)
    return value.hexdigest()

def output(value):
    sys.stdout.write(json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n'); sys.stdout.flush()

def docker(args, binary=False):
    value = subprocess.run(DOCKER + args, stdin=subprocess.DEVNULL, capture_output=True, timeout=8)
    require(value.returncode == 0, '固定daemon操作失敗')
    return value.stdout if binary else value.stdout.decode('utf-8').strip()

def process_start(pid):
    try: return pathlib.Path('/proc/%d/stat' % pid).read_text().rsplit(')', 1)[1].split()[19]
    except FileNotFoundError: return None

def marker(request):
    require(request['abi'] == 1, 'ABI不一致')
    expected = request['marker']
    require(read(OWNER) == expected, 'guest所有marker不一致')
    require(digest('/usr/lib/mcicon/guestbridge.py') == request['bridgeSha256'], 'bridge pin不一致')
    for path, sha in request['engines'].items():
        require(path in ['/usr/bin/dockerd', '/usr/bin/docker', '/usr/bin/containerd', '/usr/bin/runc'] and digest(path) == sha, 'engine pin不一致')
    require(digest('/usr/lib/mcicon/cpu-probe') == request['cpuProbeSha256'], 'CPU probe pin不一致')
    require(set(request['engines']) == {'/usr/bin/dockerd', '/usr/bin/docker', '/usr/bin/containerd', '/usr/bin/runc'}, 'engine pin不足')
    return expected

def cgroup_read(directory, name):
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
    try:
        require(stat.S_ISREG(os.fstat(descriptor).st_mode), 'cgroup file種別不明')
        value = os.read(descriptor, 4097); require(len(value) <= 4096, 'cgroup file過大')
        return value.decode('ascii').strip()
    finally: os.close(descriptor)

def cgroup_view(directory):
    root = CGROUP.parent
    with (PROC / 'self/mountinfo').open() as source:
        mountinfo = source.read(65537); require(len(mountinfo) <= 65536, 'cgroup mountinfo過大')
    rows = [row.split() for row in mountinfo.splitlines() if ' - cgroup2 ' in row]
    require(len(rows) == 1, '専用cgroup mount不明')
    row = rows[0]; separator = row.index('-')
    require(row[3] == '/' and row[4] == str(root) and set(row[5].split(',')) == {'rw', 'nosuid', 'nodev', 'noexec', 'relatime'} and row[separator+1:separator+3] == ['cgroup2', 'cgroup2'] and set(row[separator+3].split(',')) == {'rw', 'nsdelegate'}, '専用cgroup mount view不一致')
    values = []
    for name in ['self/cgroup', '1/cgroup']:
        with (PROC / name).open() as source:
            value = source.read(4097); require(len(value) <= 4096, 'cgroup所属過大'); values.append(value)
    current = pathlib.PurePosixPath(values[0].removeprefix('0::').strip())
    require(values[0].startswith('0::/') and values[0].count('\n') <= 1 and current.is_absolute() and '..' not in current.parts and str(current) == values[0][3:].strip(), '現在cgroup所属不明')
    require(re.fullmatch(r'0::/(?:\.\./){0,7}\.\.\n?', values[1]) is not None, 'PID1 cgroup view不一致')
    namespaces = [(PROC / name).readlink() for name in ['self/ns/cgroup', '1/ns/cgroup', 'self/ns/mnt', '1/ns/mnt']]
    require(namespaces[0] != namespaces[1] and all(len(str(value)) <= 512 for value in namespaces), '専用cgroup namespace不明')
    held, named = os.fstat(directory), root.stat(follow_symlinks=False)
    require((held.st_dev, held.st_ino) == (named.st_dev, named.st_ino) and not cgroup_read(directory, 'cgroup.procs') and cgroup_read(directory, 'cgroup.type') == 'domain', 'cgroup root identity/内部process不一致')
    return values, namespaces, (held.st_dev, held.st_ino)

def ensure_delegation():
    root = CGROUP.parent
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        before = cgroup_view(directory)
        required = {'cpu', 'memory', 'pids'}; known = required | {'cpuset', 'io', 'hugetlb', 'rdma'}
        available = set(cgroup_read(directory, 'cgroup.controllers').split())
        enabled = set(cgroup_read(directory, 'cgroup.subtree_control').split())
        require(required <= available <= known and enabled <= available, 'cgroup controller不明')
        if required <= enabled: return
        require(not enabled and before[0][0] == '0::/non-systemd\n', 'cgroup partial供給の自動修復拒否')
        with os.scandir(directory) as entries:
            names = []
            for index, entry in enumerate(entries):
                require(index < 256 and not entry.is_symlink(), 'cgroup root entry不明')
                if entry.is_dir(follow_symlinks=False): names.append(entry.name)
        require(names == ['non-systemd'], '初回cgroup root構造不一致')
        leaf = os.open('non-systemd', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
        try:
            require(cgroup_read(leaf, 'cgroup.type') == 'domain' and not cgroup_read(leaf, 'cgroup.subtree_control'), '初回leaf構造不一致')
        finally: os.close(leaf)
        require(cgroup_view(directory) == before and not cgroup_read(directory, 'cgroup.subtree_control'), '供給前cgroup view変更')
        descriptor = os.open('cgroup.subtree_control', os.O_WRONLY | os.O_NOFOLLOW, dir_fd=directory)
        try:
            require(stat.S_ISREG(os.fstat(descriptor).st_mode), 'cgroup書込先不明')
            value = b'+cpu +memory +pids\n'; require(os.write(descriptor, value) == len(value), 'cgroup供給書込不明')
        finally: os.close(descriptor)
        require(cgroup_view(directory) == before and set(cgroup_read(directory, 'cgroup.subtree_control').split()) == required, 'cgroup供給readback不一致')
    finally: os.close(directory)

def daemon(owner, restart_allowed, allow_start=True):
    ROOT.mkdir(mode=0o700, exist_ok=True)
    pidfile = ROOT / 'daemon.json'
    label = 'io.github.rs0325.mcicon.generation=' + owner['generationOwner']
    if pidfile.exists():
        record = read(pidfile)
        require(record['generation'] == owner['generationOwner'], 'daemon世代不一致')
        if record['boot'] == BOOT.read_text().strip():
            if process_start(record['pid']) == record['started']:
                require(label in json.loads(docker(['info', '--format', '{{json .Labels}}'])), 'daemon label不一致'); return
            require(restart_allowed, '停止receiptのないdaemon消失またはPID再使用')
    require(allow_start, '回復中のdaemon新生成拒否')
    ensure_delegation()
    pathlib.Path('/run/mcicon').mkdir(mode=0o700, exist_ok=True)
    log = (ROOT / 'dockerd.log').open('ab', buffering=0)
    child = subprocess.Popen(['/usr/bin/dockerd', '--host', 'unix://' + SOCKET, '--data-root', str(ROOT / 'docker'), '--exec-root', '/run/mcicon/docker', '--pidfile', '/run/mcicon/dockerd.pid', '--bridge', 'none', '--iptables=false', '--ip6tables=false', '--ip-forward=false', '--ip-masq=false', '--userland-proxy=false', '--label', label], stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    started = process_start(child.pid); require(started is not None, 'daemon開始identity不明')
    write(pidfile, {'generation': owner['generationOwner'], 'boot': BOOT.read_text().strip(), 'pid': child.pid, 'started': started})
    end = time.monotonic() + 20
    while time.monotonic() < end:
        require(child.poll() is None, 'daemon開始失敗')
        try:
            if label in json.loads(docker(['info', '--format', '{{json .Labels}}'])): return
        except (RuntimeError, subprocess.TimeoutExpired): pass
        time.sleep(.1)
    raise RuntimeError('daemon開始期限超過')

def job_directory(request):
    job, owner = request['jobId'], request['ownerId']
    require(str(uuid.UUID(job)) == job and str(uuid.UUID(owner)) == owner and job != owner, 'job identity不明')
    jobs = ROOT / 'jobs'; jobs.mkdir(mode=0o700, exist_ok=True)
    path = jobs / job; path.mkdir(mode=0o700, exist_ok=True); require(not path.is_symlink(), 'job link拒否')
    return path

def identity(request): return {key: request[key] for key in ['jobId', 'ownerId', 'descriptorSha256']}

def owned(request, fullid):
    require(len(fullid) == 64 and all(c in '0123456789abcdef' for c in fullid), 'full ID不明')
    ids = docker(['container', 'ls', '--all', '--quiet', '--no-trunc', '--filter', 'id=' + fullid]).splitlines()
    if not ids: return None
    require(ids == [fullid], 'full ID一覧不一致')
    value = json.loads(docker(['container', 'inspect', fullid, '--format', '{{json .}}']))
    require(value['Id'] == fullid, 'container ID不一致')
    labels = value['Config']['Labels']
    for key, val in identity(request).items(): require(labels.get('io.github.rs0325.mcicon.' + key) == val, 'container所有label不一致')
    require(labels.get('io.github.rs0325.mcicon.generation') == request['marker']['generationOwner'], 'container世代label不一致')
    return value

def keep(request, owner, recovery):
    path = job_directory(request)
    attempt = request['keeperAttempt']; require(str(uuid.UUID(attempt)) == attempt, 'keeper attempt不明')
    with (path / 'fence.lock').open('a+b') as fence:
        fcntl.flock(fence, fcntl.LOCK_EX)
        record_path = path / 'fence.json'
        record = read(record_path) if record_path.exists() else {'identity': identity(request), 'closed': recovery, 'containers': [], 'actors': []}
        require(record['identity'] == identity(request), 'keeper fence所有不一致')
        if recovery:
            record['closed'] = True; write(record_path, record)
        else: require(not record['closed'], 'closed fenceへのkeeper生成拒否')
        group = CGROUP / owner['generationOwner'] / 'keepers' / request['jobId']
        previous = record.get('keeper')
        if previous is not None:
            require(recovery and previous['group'] == str(group), 'keeper重複生成拒否')
            require(previous['boot'] != BOOT.read_text().strip() or process_start(previous['pid']) != previous['started'], '稼働keeperの再採用拒否')
        require(not group.is_symlink(), 'keeper group link拒否')
        if group.exists():
            require(previous is not None and group.resolve() == group and not (group / 'cgroup.procs').read_text().strip(), 'keeper group再使用不明')
        else: group.mkdir(parents=True)
        require(group.resolve() == group and (group / 'cgroup.kill').is_file(), 'keeper cgroup2不明')
        current = {'attempt': attempt, 'identity': identity(request), 'group': str(group), 'boot': BOOT.read_text().strip(), 'pid': os.getpid(), 'started': process_start(os.getpid())}
        require(current['started'] is not None, 'keeper開始identity不明')
        with (path / ('keeper-' + attempt + '.json')).open('x') as saved:
            json.dump(current, saved, ensure_ascii=False, separators=(',', ':')); saved.flush(); os.fsync(saved.fileno())
        record['keeper'] = current; write(record_path, record)
        (group / 'cgroup.procs').write_text(str(os.getpid()))
        return current

def wait_keeper(request):
    while os.read(0, 1): pass
    # workのclosed/清算とは独立したlifetime参照の終端だけを扱う。
    return 0

def main(request):
    operation = request['operation']; ROOT.mkdir(mode=0o700, exist_ok=True)
    with (ROOT / 'lifecycle.lock').open('a+b') as lifecycle:
        fcntl.flock(lifecycle, fcntl.LOCK_EX)
        if operation in ['init', 'keep-init']:
            require(not OWNER.exists(), '既存guest marker上書き拒否')
            require(request['abi'] == 1, 'ABI不一致'); write(OWNER, request['marker'])
        owner = marker(request)
        if operation in ['hello', 'init']:
            ensure_delegation()
            memory = int(next(row.split()[1] for row in pathlib.Path('/proc/meminfo').read_text().splitlines() if row.startswith('MemTotal:'))) * 1024
            require(memory >= request['memoryBytes'] and os.cpu_count() >= 4, 'guest資源不足')
            output({'abi': 1, 'marker': owner, 'bridgeSha256': request['bridgeSha256'], 'memoryBytes': memory, 'cpus': os.cpu_count(), 'boot': BOOT.read_text().strip()}); return
        if operation in ['keep-init', 'keep', 'keep-recover']:
            if operation != 'keep-recover': ensure_delegation()
            else:
                descriptor = os.open(CGROUP.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try: cgroup_view(descriptor)
                finally: os.close(descriptor)
            memory = int(next(row.split()[1] for row in (PROC / 'meminfo').read_text().splitlines() if row.startswith('MemTotal:'))) * 1024
            if operation != 'keep-recover': require(memory >= request['memoryBytes'] and os.cpu_count() >= 4, 'keeper guest資源不足')
            keeper = keep(request, owner, operation == 'keep-recover')
            output({'abi': 1, 'identity': identity(request), 'marker': owner, 'bridgeSha256': request['bridgeSha256'], 'boot': BOOT.read_text().strip(), 'memoryBytes': memory, 'cpus': os.cpu_count(), 'keeper': keeper})
            fcntl.flock(lifecycle, fcntl.LOCK_UN)
            return wait_keeper(request)
        if operation != 'release-recover': daemon(owner, request['restartAllowed'])
        if operation == 'load':
            child = subprocess.run(DOCKER + ['image', 'load'], stdin=0, stdout=sys.stderr, stderr=sys.stderr, timeout=request['timeoutSeconds'])
            require(child.returncode == 0, 'image load失敗'); output(json.loads(docker(['image', 'inspect', request['imageId'], '--format', '{{json .}}']))); return
        path = job_directory(request)
        with (path / 'fence.lock').open('a+b') as fence:
            fcntl.flock(fence, fcntl.LOCK_EX)
            record_path = path / 'fence.json'
            if record_path.exists():
                record = read(record_path); require(record['identity'] == identity(request), 'fence所有不一致')
            else:
                require(operation != 'release-recover', '回復fence欠落')
                record = {'identity': identity(request), 'closed': False, 'containers': [], 'actors': []}
            if operation in ['release', 'release-recover']:
                if operation == 'release-recover':
                    require(record['closed'], '回復work fence未閉鎖')
                    require('pendingCreate' not in record, 'daemon create終端不明のためintentを保持')
                    if record['containers']: daemon(owner, False, False)
                record['closed'] = True; write(record_path, record)
                guest_boot = BOOT.read_text().strip()
                for actor in record['actors']:
                    if actor['boot'] != guest_boot: continue
                    group = CGROUP / owner['generationOwner'] / request['jobId']
                    require(str(group) == actor['cgroup'] and group.is_dir() and group.resolve() == group and (group / 'cgroup.kill').is_file(), 'guest fence cgroup不明')
                    (group / 'cgroup.kill').write_text('1')
                    end = time.monotonic() + 3
                    while 'populated 0' not in (group / 'cgroup.events').read_text().splitlines():
                        require(time.monotonic() < end, 'guest fence終端未確認'); time.sleep(.02)
                require('pendingCreate' not in record, 'daemon create終端不明のためintentを保持')
                for item in record['containers']:
                    if owned(request, item['id']) is not None: require(docker(['container', 'rm', '--force', item['id']]) == item['id'], 'rm full ID不一致')
                    require(owned(request, item['id']) is None, 'container不存在未確認')
                record['actors'] = []; record['containers'] = []; write(record_path, record); output({'closed': True, 'actors': 0, 'containers': 0, 'identity': identity(request), 'keeper': record.get('keeper')}); return
            if operation == 'remove':
                require('pendingCreate' not in record, '未確定createはjob releaseが必要です')
                fullid = request['id']; require(any(item['id'] == fullid for item in record['containers']), '未登録container削除拒否')
                if owned(request, fullid) is not None: require(docker(['container', 'rm', '--force', fullid]) == fullid, 'rm full ID不一致')
                require(owned(request, fullid) is None, 'container不存在未確認')
                record['containers'] = [item for item in record['containers'] if item['id'] != fullid]; write(record_path, record); output({'removed': fullid}); return
            require(not record['closed'], 'closed fenceへの生成拒否')
            if operation == 'snapshot':
                cache = path / 'cache'; require(not cache.exists(), 'snapshot重複拒否'); cache.mkdir(mode=0o700)
                with tarfile.open(fileobj=os.fdopen(os.dup(0), 'rb', buffering=0), mode='r|') as archive:
                    for entry in archive:
                        target = pathlib.PurePosixPath(entry.name)
                        require(not target.is_absolute() and '..' not in target.parts and entry.isfile(), 'snapshot tar entry拒否')
                        destination = cache / target; destination.parent.mkdir(parents=True, exist_ok=True)
                        with destination.open('xb') as output_file, archive.extractfile(entry) as source:
                            while chunk := source.read(65536): output_file.write(chunk)
                output({'snapshot': True}); return
            if operation == 'fetch':
                relative = pathlib.PurePosixPath(request['path']); require(not relative.is_absolute() and '..' not in relative.parts and str(relative).endswith('.png'), 'PNG path拒否')
                directory = os.open(path / 'cache', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    for part in relative.parts[:-1]:
                        next_directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory); os.close(directory); directory = next_directory
                    descriptor = os.open(relative.parts[-1], os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
                finally: os.close(directory)
                with os.fdopen(descriptor, 'rb') as source_file:
                    info = os.fstat(source_file.fileno()); require(stat.S_ISREG(info.st_mode) and info.st_size <= 67108864, 'PNG実体拒否')
                    while chunk := source_file.read(65536): sys.stdout.buffer.write(chunk)
                sys.stdout.buffer.flush(); return
            if operation == 'inspect':
                require(request['args'][:2] == ['image', 'inspect'], 'inspect argv拒否'); output({'stdout': docker(request['args'])}); return
            if operation == 'create':
                args = request['args']; require(args[0] == 'create', 'create argv拒否')
                name = args[args.index('--name') + 1]
                require(name.startswith(('mcicon-worker-', 'mcicon-doctor-', 'mcicon-cpu-')), 'name拒否')
                require(not any(item['name'] == name for item in record['containers']), 'name重複')
                # daemon受理前にintentを保存し、曖昧なcreateは自動再実行しない。
                record['pendingCreate'] = name; write(record_path, record)
                fullid = docker(args); value = owned(request, fullid); require(value is not None and value['Name'] == '/' + name, 'create所有不一致')
                record['containers'].append({'name': name, 'id': fullid}); del record['pendingCreate']; write(record_path, record); output({'id': fullid}); return
            if operation == 'start':
                require('pendingCreate' not in record, '未確定createあり'); fullid = request['id']; require(any(item['id'] == fullid for item in record['containers']), '未登録container')
                require(owned(request, fullid) is not None, 'container所有未確認')
                args = DOCKER + ['container', 'start', '--attach'] + (['--interactive'] if request['interactive'] else []) + [fullid]
                group = CGROUP / owner['generationOwner'] / request['jobId']
                group.mkdir(parents=True, exist_ok=True)
                require(group.resolve() == group and (group / 'cgroup.kill').is_file(), 'guest fence cgroup2が必要です')
                record['actors'].append({'cgroup': str(group), 'boot': BOOT.read_text().strip()}); write(record_path, record)
                (group / 'cgroup.procs').write_text(str(os.getpid()))
                child = subprocess.Popen(args, stdin=0 if request['interactive'] else subprocess.DEVNULL, stdout=1, stderr=2)
            else: raise RuntimeError('未対応bridge操作')
        # streaming中はfence/lifecycleを解放し、取消が同じjobを閉じられるようにする。
        fcntl.flock(lifecycle, fcntl.LOCK_UN)
        return child.wait()

def cli():
    try:
        if os.getpgrp() != os.getpid(): os.setsid()
        data = bytearray()
        while True:
            byte = os.read(0, 1)
            require(byte, 'bridge envelope不足')
            if byte == b'\n': break
            data += byte; require(len(data) <= 1048576, 'bridge envelope過大')
        result = main(json.loads(data.decode('utf-8')))
        sys.exit(0 if result is None else result)
    except Exception:
        print('専用guestbridge操作に失敗しました', file=sys.stderr); sys.exit(1)

if __name__ == '__main__': cli()
