"""実guestbridge関数を隔離filesystemとDocker境界で検査する。kernel/WSL受入ではない。"""
import contextlib, importlib.util, io, json, pathlib, tempfile, unittest, uuid, os, shutil
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).parents[1] / 'src/main/guest/guestbridge.py'
spec = importlib.util.spec_from_file_location('guestbridge', SOURCE)
bridge = importlib.util.module_from_spec(spec); spec.loader.exec_module(bridge)
REAL_DAEMON = bridge.daemon

class FenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temporary.name).resolve()
        self.request = {'abi': 1, 'operation': 'release', 'jobId': str(uuid.uuid4()), 'ownerId': str(uuid.uuid4()), 'descriptorSha256': 'a' * 64, 'marker': {'generationOwner': str(uuid.uuid4())}, 'restartAllowed': False, 'timeoutSeconds': 60}
        (self.root / "boot").write_text("fixture-boot")
        self.patches = [patch.object(bridge, "BOOT", self.root / "boot"), patch.object(bridge, 'ROOT', self.root / 'state'), patch.object(bridge, 'CGROUP', self.root / 'cgroup'), patch.object(bridge, 'marker', return_value=self.request['marker']), patch.object(bridge, 'daemon')]
        for item in self.patches: item.start()
        bridge.ROOT.mkdir()
        self.path = bridge.job_directory(self.request)
        self.record = {'identity': bridge.identity(self.request), 'closed': False, 'containers': [], 'actors': []}
    def tearDown(self):
        for item in reversed(self.patches): item.stop()
        self.temporary.cleanup()
    def save(self): bridge.write(self.path / 'fence.json', self.record)
    def call(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output): bridge.main(self.request)
        return json.loads(output.getvalue())
    def test_release_closes_before_later_generation_and_preserves_foreign_identity(self):
        self.save(); self.assertEqual(self.call()['actors'], 0)
        self.request['operation'] = 'create'; self.request['args'] = ['create']
        with self.assertRaisesRegex(RuntimeError, 'closed fence'): self.call()
        self.request['operation'] = 'release'; self.request['ownerId'] = str(uuid.uuid4())
        with self.assertRaisesRegex(RuntimeError, 'fence所有不一致'): self.call()
    def test_release_needs_cgroup_empty_proof_and_does_not_erase_failed_evidence(self):
        group = bridge.CGROUP / self.request['marker']['generationOwner'] / self.request['jobId']; group.mkdir(parents=True)
        (group / 'cgroup.kill').write_text(''); (group / 'cgroup.events').write_text('populated 1\n')
        self.record['actors'] = [{'boot': 'fixture-boot', 'cgroup': str(group)}]; self.save()
        with patch.object(bridge.time, 'monotonic', side_effect=[0, 4]):
            with self.assertRaisesRegex(RuntimeError, 'guest fence終端未確認'): self.call()
        self.assertTrue(bridge.read(self.path / 'fence.json')['closed']); self.assertEqual(len(bridge.read(self.path / 'fence.json')['actors']), 1)
    def test_uncertain_create_does_not_clear_intent_when_name_temporarily_absent(self):
        self.record['pendingCreate'] = 'mcicon-worker-' + self.request['jobId']; self.save()
        with patch.object(bridge, 'docker', return_value='') as docker:
            with self.assertRaisesRegex(RuntimeError, 'daemon create終端不明'): self.call()
        docker.assert_not_called()
        current = bridge.read(self.path / 'fence.json')
        self.assertTrue(current['closed']); self.assertEqual(current['pendingCreate'], self.record['pendingCreate'])

class CreateBudgetTest(unittest.TestCase):
    setUp = FenceTest.setUp
    tearDown = FenceTest.tearDown
    save = FenceTest.save
    call = FenceTest.call
    def create(self, budget):
        self.request.update(operation='create', args=['create', '--name', 'mcicon-cpu-' + self.request['jobId']], timeoutSeconds=budget)
        self.save()
    def test_create_near_deadline_runs_real_daemon_ownership_and_commits_with_reserved_time(self):
        for budget in [30, 15]:
            with self.subTest(budget=budget): self.exercise(budget, 'normal')
    def test_ownership_timeout_keeps_pending_and_save_overrun_keeps_only_confirmed_full_id(self):
        for mode in ['inspect-timeout', 'save-failure', 'save-overrun']:
            with self.subTest(mode=mode): self.exercise(30, mode)
    def exercise(self, budget, mode):
        self.create(budget); now = [100.0]; calls = []; fullid = 'a'*64
        bridge.write(bridge.ROOT / 'daemon.json', {'generation':self.request['marker']['generationOwner'], 'boot':'fixture-boot','pid':777,'started':'300'})
        original_write = bridge.write
        def run(args, **kwargs):
            operation = args[len(bridge.DOCKER):]; calls.append((operation, kwargs['timeout']))
            if operation[0] == 'info': duration, result = .1, json.dumps(['io.github.rs0325.mcicon.generation='+self.request['marker']['generationOwner']])
            elif operation[0] == 'create':
                duration, result = budget - 3.2, fullid
                self.assertGreater(kwargs['timeout'], 8.55)
            elif operation[:2] == ['container', 'ls']: duration, result = .8, fullid
            elif operation[:2] == ['container', 'inspect']:
                duration = 1.4 if mode == 'inspect-timeout' else .8
                labels = {'io.github.rs0325.mcicon.'+key:value for key,value in bridge.identity(self.request).items()}; labels['io.github.rs0325.mcicon.generation'] = self.request['marker']['generationOwner']
                result = json.dumps({'Id':fullid,'Name':'/mcicon-cpu-'+self.request['jobId'],'Config':{'Labels':labels}})
            else: self.fail(operation)
            if duration >= kwargs['timeout']:
                now[0] += kwargs['timeout']; raise bridge.subprocess.TimeoutExpired(args, kwargs['timeout'])
            now[0] += duration
            return type('Result', (), {'returncode':0, 'stdout':result.encode()})()
        def write(path, value):
            if path == self.path / 'fence.json' and 'pendingCreate' not in value:
                if mode == 'save-failure': raise OSError('fixture save failure')
                original_write(path, value); now[0] += 2 if mode == 'save-overrun' else .2
            else: original_write(path, value)
        output = io.StringIO()
        with patch.object(bridge.time, 'monotonic', side_effect=lambda:now[0]), patch.object(bridge, 'daemon', side_effect=REAL_DAEMON), patch.object(bridge, 'process_start', return_value='300'), patch.object(bridge.subprocess, 'run', side_effect=run), patch.object(bridge, 'write', side_effect=write), contextlib.redirect_stdout(output):
            if mode == 'normal': bridge.main(self.request)
            else:
                with self.assertRaises(OSError if mode == 'save-failure' else bridge.subprocess.TimeoutExpired): bridge.main(self.request)
        saved = bridge.read(self.path / 'fence.json')
        if mode in ['normal', 'save-overrun']:
            self.assertNotIn('pendingCreate', saved); self.assertEqual(saved['containers'], [{'name':'mcicon-cpu-'+self.request['jobId'], 'id':fullid}])
        else: self.assertIn('pendingCreate', saved); self.assertEqual(saved['containers'], [])
        if mode == 'normal': self.assertEqual(json.loads(output.getvalue())['id'],fullid); self.assertLess(now[0],100+budget)
        else: self.assertEqual(output.getvalue(),'')
        self.assertLessEqual(calls[-1][1],2); self.assertEqual(len(calls),4)
    def test_expired_create_never_checks_daemon_or_spawns_or_creates_intent(self):
        self.create(-1)
        with patch.object(bridge, 'daemon', side_effect=REAL_DAEMON), patch.object(bridge.subprocess, 'run') as run:
            bridge.write(bridge.ROOT / 'daemon.json', {'generation':self.request['marker']['generationOwner'], 'boot':'fixture-boot','pid':777,'started':'300'})
            with patch.object(bridge, 'process_start', return_value='300'):
                with self.assertRaises(bridge.subprocess.TimeoutExpired): self.call()
            run.assert_not_called()
        self.assertNotIn('pendingCreate', bridge.read(self.path / 'fence.json'))
    def test_cli_reports_safe_type_and_chained_timeout_without_arguments(self):
        chained = bridge.BridgeFailure('DAEMON_PREPARATION_UNKNOWN', 'secret /private/token'); chained.__cause__ = bridge.subprocess.TimeoutExpired(['/private/token'], 8)
        for failure in [bridge.subprocess.TimeoutExpired(['/private/token'], 8), ValueError('secret /private/token'), chained]:
            error = io.StringIO()
            with patch.object(bridge.os, 'getpgrp', return_value=1), patch.object(bridge.os, 'getpid', return_value=1), patch.object(bridge.os, 'read', return_value=b'\n'), patch.object(bridge, 'main', side_effect=failure), patch.object(bridge.json, 'loads', return_value={}), contextlib.redirect_stderr(error):
                with self.assertRaises(SystemExit): bridge.cli()
            self.assertIn(type(failure).__name__, error.getvalue())
            self.assertIn('timeout=' + str(isinstance(failure, bridge.subprocess.TimeoutExpired) or failure is chained).lower(), error.getvalue())
            self.assertNotIn('private', error.getvalue()); self.assertNotIn('secret', error.getvalue())


class KeeperTest(unittest.TestCase):
    setUp = FenceTest.setUp
    tearDown = FenceTest.tearDown
    save = FenceTest.save
    call = FenceTest.call
    def run_keeper(self, operation, previous_dead=False):
        self.request.update(operation=operation, keeperAttempt=str(uuid.uuid4()), bridgeSha256='b'*64, memoryBytes=1)
        proc = self.root / 'proc'; proc.mkdir(exist_ok=True); (proc / 'meminfo').write_text('MemTotal: 8388608 kB\n')
        original = pathlib.Path.mkdir
        def mkdir(path, *args, **kwargs):
            original(path, *args, **kwargs)
            if path.name == self.request['jobId'] and path.parent.name == 'keepers':
                (path / 'cgroup.kill').write_text(''); (path / 'cgroup.procs').write_text('')
        def waited(request):
            import fcntl
            for path in [bridge.ROOT / 'lifecycle.lock', self.path / 'fence.lock']:
                with path.open('a+b') as check: fcntl.flock(check, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return 0
        with patch.object(bridge, 'PROC', proc), patch.object(bridge, 'OWNER', bridge.ROOT / 'owner.json'), patch.object(bridge, 'ensure_delegation') as delegation, patch.object(bridge, 'cgroup_view'), patch.object(bridge, 'process_start', side_effect=lambda pid: None if previous_dead and pid == 123 else '200' if previous_dead else '100'), patch.object(bridge.os, 'getpid', return_value=124 if previous_dead else 123), patch.object(pathlib.Path, 'mkdir', mkdir), patch.object(bridge, 'wait_keeper', side_effect=waited):
            value = self.call()
            self.assertEqual(delegation.call_count, 0 if operation == 'keep-recover' and 'stop' not in self.request else 1)
        return value
    def test_first_init_registers_keeper_before_ack_and_waits_without_locks(self):
        value = self.run_keeper('keep-init')
        record = bridge.read(self.path / 'fence.json')
        self.assertFalse(record['closed']); self.assertEqual(record['actors'], [])
        self.assertEqual(value['keeper'], record['keeper'])
        self.assertEqual(pathlib.Path(record['keeper']['group']).parts[-2], 'keepers')
        self.assertTrue((self.path / ('keeper-'+self.request['keeperAttempt']+'.json')).is_file())
    def test_cleanup_only_starts_with_closed_work_fence(self):
        value = self.run_keeper('keep-recover')
        self.assertTrue(bridge.read(self.path / 'fence.json')['closed'])
        self.assertEqual(value['identity'], bridge.identity(self.request))
    def test_normal_keeper_cannot_reopen_closed_fence(self):
        self.record['closed'] = True; self.save()
        with self.assertRaisesRegex(RuntimeError, 'closed fence'): self.run_keeper('keep')
    def test_foreign_identity_and_live_previous_keeper_are_not_adopted(self):
        self.record['identity']['ownerId'] = str(uuid.uuid4()); self.save()
        with self.assertRaisesRegex(RuntimeError, 'keeper fence所有'): self.run_keeper('keep-recover')
        self.record['identity'] = bridge.identity(self.request)
        group = bridge.CGROUP / self.request['marker']['generationOwner'] / 'keepers' / self.request['jobId']
        self.record['keeper'] = {'group':str(group), 'boot':'fixture-boot', 'pid':123, 'started':'100'}; self.save()
        with self.assertRaisesRegex(RuntimeError, '稼働keeper'): self.run_keeper('keep-recover')
        self.assertTrue(bridge.read(self.path / 'fence.json')['closed'])
    def test_work_release_preserves_separate_keeper_record_and_group(self):
        self.run_keeper('keep')
        before = bridge.read(self.path / 'fence.json')['keeper']
        self.request['operation'] = 'release'; value = self.call()
        self.assertEqual(value['keeper'], before)
        self.assertEqual((pathlib.Path(before['group']) / 'cgroup.procs').read_text(), '123')
        self.assertEqual(value['actors'], 0)
    def recover_after_init(self):
        self.run_keeper('keep-init')
        previous = bridge.read(self.path / 'fence.json')['keeper']
        (pathlib.Path(previous['group']) / 'cgroup.procs').write_text('')
        self.run_keeper('keep-recover', previous_dead=True)
        self.request['operation'] = 'release-recover'
    def test_confirmed_stop_supplies_delegation_before_recovery_keeper_creation(self):
        self.request['stop']={'id':str(uuid.uuid4()),'boot':str(uuid.uuid4())}
        self.run_keeper('keep-recover')
        self.assertTrue(bridge.read(self.path / 'fence.json')['closed'])
    def test_invalid_recovery_stop_never_supplies_delegation(self):
        self.request['stop']={'id':'invalid','boot':str(uuid.uuid4())}
        with self.assertRaises(ValueError): self.run_keeper('keep-recover')
        self.assertFalse((self.path / 'fence.json').exists())
    def test_init_ack_core_death_recovery_release_does_not_start_daemon(self):
        self.recover_after_init()
        with patch.object(bridge, 'daemon', side_effect=REAL_DAEMON), patch.object(bridge.subprocess, 'Popen') as spawn, patch.object(bridge, 'docker') as docker:
            value = self.call()
            spawn.assert_not_called(); docker.assert_not_called()
        self.assertTrue(value['closed']); self.assertEqual(value['actors'], 0)
        self.assertFalse((bridge.ROOT / 'daemon.json').exists())
    def test_recovery_container_cleanup_uses_only_verified_existing_daemon(self):
        self.recover_after_init(); fullid = 'a'*64
        record = bridge.read(self.path / 'fence.json'); record['containers'] = [{'id': fullid}]; bridge.write(self.path / 'fence.json', record)
        bridge.write(bridge.ROOT / 'daemon.json', {'generation':self.request['marker']['generationOwner'],'boot':'fixture-boot','pid':777,'started':'300'})
        removed = False
        def docker(args, **kwargs):
            nonlocal removed
            if args[0] == 'info': return json.dumps(['io.github.rs0325.mcicon.generation='+self.request['marker']['generationOwner']])
            if args[:2] == ['container','ls']: return '' if removed else fullid
            if args[:2] == ['container','inspect']:
                labels = {'io.github.rs0325.mcicon.'+key:value for key,value in bridge.identity(self.request).items()}; labels['io.github.rs0325.mcicon.generation'] = self.request['marker']['generationOwner']
                return json.dumps({'Id':fullid,'Config':{'Labels':labels}})
            if args[:2] == ['container','rm']: removed = True; return fullid
            self.fail('unexpected Docker operation')
        with patch.object(bridge, 'daemon', side_effect=REAL_DAEMON), patch.object(bridge, 'process_start', return_value='300'), patch.object(bridge, 'docker', side_effect=docker), patch.object(bridge.subprocess, 'Popen') as spawn:
            self.assertEqual(self.call()['containers'],0); spawn.assert_not_called()
        self.assertTrue(removed)
    def test_confirmed_stop_recovery_clears_old_actors_and_reclaims_full_id(self):
        self.recover_after_init()
        keeper = bridge.read(self.path / 'fence.json')['keeper']
        record = bridge.read(self.path / 'fence.json')
        record.update(actors=[{'boot':'fixture-boot','cgroup':'missing-after-stop'}], containers=[{'id':'a'*64}])
        bridge.write(self.path / 'fence.json',record)
        keeper['stop']={'id':str(uuid.uuid4()),'boot':str(uuid.uuid4())}; record['keeper']=keeper; bridge.write(self.path / 'fence.json',record)
        self.request.update(operation='daemon-recover', claim={'identity':bridge.identity(self.request), 'keeperAttempt':keeper['attempt'], 'stop':keeper['stop']})
        daemon = {'generation':self.request['marker']['generationOwner'], 'boot':'fixture-boot','pid':777,'started':'300'}
        with patch.object(bridge,'process_start',return_value=keeper['started']), patch.object(bridge,'daemon',return_value=daemon) as prepare:
            self.assertTrue(self.call()['ready']); prepare.assert_called_once_with(self.request['marker'],True)
        self.assertEqual(bridge.read(self.path / 'fence.json')['actors'],[])
        self.request['operation'] = 'release-recover'
        with patch.object(bridge,'owned',return_value=None):
            self.assertEqual(self.call()['containers'],0)
    def test_recovery_stop_does_not_clear_pending_create_or_foreign_fence(self):
        self.recover_after_init()
        keeper = bridge.read(self.path / 'fence.json')['keeper']
        record = bridge.read(self.path / 'fence.json'); record['pendingCreate']='uncertain'
        bridge.write(self.path / 'fence.json',record)
        keeper['stop']={'id':str(uuid.uuid4()),'boot':str(uuid.uuid4())}; record['keeper']=keeper; bridge.write(self.path / 'fence.json',record)
        self.request.update(operation='daemon-recover', claim={'identity':bridge.identity(self.request), 'keeperAttempt':keeper['attempt'], 'stop':keeper['stop']})
        with patch.object(bridge,'process_start',return_value=keeper['started']), patch.object(bridge,'daemon') as daemon:
            with self.assertRaisesRegex(RuntimeError,'未確定'): self.call()
            daemon.assert_not_called()
        self.assertEqual(bridge.read(self.path / 'fence.json'),record)
    def test_recovery_missing_or_stale_daemon_and_pending_create_never_spawn(self):
        self.recover_after_init(); record = bridge.read(self.path / 'fence.json'); record['containers'] = [{'id':'a'*64}]; bridge.write(self.path / 'fence.json',record)
        for old_boot in [None, 'old-boot', 'fixture-boot']:
            pidfile = bridge.ROOT / 'daemon.json'
            if old_boot is not None: bridge.write(pidfile, {'generation':self.request['marker']['generationOwner'],'boot':old_boot,'pid':777,'started':'300'})
            with patch.object(bridge, 'daemon', side_effect=REAL_DAEMON), patch.object(bridge, 'process_start', return_value=None), patch.object(bridge.subprocess, 'Popen') as spawn, patch.object(bridge, 'docker') as docker:
                with self.assertRaises(RuntimeError): self.call()
                spawn.assert_not_called(); docker.assert_not_called()
            self.assertTrue(bridge.read(self.path / 'fence.json')['closed']); self.assertEqual(len(bridge.read(self.path / 'fence.json')['containers']),1)
        record['pendingCreate'] = 'uncertain'; bridge.write(self.path / 'fence.json',record)
        with patch.object(bridge, 'daemon', side_effect=REAL_DAEMON) as daemon, patch.object(bridge.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError,'create終端不明'): self.call()
            daemon.assert_not_called(); spawn.assert_not_called()
    def test_recovery_release_requires_existing_closed_fence(self):
        self.request['operation'] = 'release-recover'
        with patch.object(bridge.subprocess,'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError,'回復fence欠落'): self.call()
            self.save()
            with self.assertRaisesRegex(RuntimeError,'未閉鎖'): self.call()
            spawn.assert_not_called()

class DelegationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temporary.name).resolve()
        self.group = self.root / 'cgroup'; self.proc = self.root / 'proc'; self.make_group()
        for name, value in [('self/cgroup', '0::/non-systemd\n'), ('1/cgroup', '0::/../../..\n')]:
            path = self.proc / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value)
        for name, value in [('self/ns/cgroup', 'cgroup:[1]'), ('1/ns/cgroup', 'cgroup:[2]'), ('self/ns/mnt', 'mnt:[3]'), ('1/ns/mnt', 'mnt:[4]')]:
            path = self.proc / name; path.parent.mkdir(parents=True, exist_ok=True); path.symlink_to(value)
        self.mountinfo()
        self.patches = [patch.object(bridge, 'CGROUP', self.group / 'mcicon'), patch.object(bridge, 'PROC', self.proc)]
        for item in self.patches: item.start()
        self.commands = []; self.original_write = os.write
    def tearDown(self):
        for item in reversed(self.patches): item.stop()
        self.temporary.cleanup()
    def make_group(self):
        self.group.mkdir(); leaf = self.group / 'non-systemd'; leaf.mkdir()
        for directory in [self.group, leaf]:
            for name, value in [('cgroup.type', 'domain\n'), ('cgroup.procs', ''), ('cgroup.subtree_control', '')]: (directory / name).write_text(value)
        (self.group / 'cgroup.controllers').write_text('cpuset cpu io memory hugetlb pids rdma\n')
    def mountinfo(self):
        (self.proc / 'self').mkdir(parents=True, exist_ok=True)
        (self.proc / 'self/mountinfo').write_text('1 0 0:1 / '+str(self.group)+' rw,nosuid,nodev,noexec,relatime - cgroup2 cgroup2 rw,nsdelegate\n')
    def write(self, descriptor, value):
        self.assertEqual(os.fstat(descriptor).st_ino, (self.group / 'cgroup.subtree_control').stat().st_ino)
        self.commands.append(value); self.original_write(descriptor, b'cpu memory pids\n'); return len(value)
    def ensure(self):
        with patch.object(bridge.os, 'write', side_effect=self.write): bridge.ensure_delegation()
    def test_empty_enable_then_repeated_readonly(self):
        self.ensure(); self.ensure(); self.assertEqual(self.commands, [b'+cpu +memory +pids\n'])
    def test_same_boot_recreated_root_is_not_cached(self):
        self.ensure(); shutil.rmtree(self.group); self.make_group(); self.ensure(); self.assertEqual(len(self.commands), 2)
    def test_builder_or_active_job_with_supplied_controllers_is_readonly(self):
        for path in ['/mcicon-builder-fixture', '/non-systemd']:
            (self.proc / 'self/cgroup').write_text('0::'+path+'\n')
            (self.group / 'cgroup.subtree_control').write_text('cpu memory pids io\n')
            (self.group / 'docker').mkdir(exist_ok=True); (self.group / 'mcicon').mkdir(exist_ok=True)
            self.ensure()
        self.assertFalse(self.commands)
    def test_partial_unavailable_or_unknown_controller_never_write(self):
        for name, value in [('cgroup.subtree_control', 'cpu'), ('cgroup.controllers', 'cpu memory'), ('cgroup.controllers', 'cpu memory pids unknown')]:
            path = self.group / name; before = path.read_text(); path.write_text(value)
            with self.assertRaises(RuntimeError): self.ensure()
            path.write_text(before)
        self.assertFalse(self.commands)
    def test_unsafe_view_root_process_or_leaf_structure_never_write(self):
        for path, value in [(self.proc / 'self/cgroup', '0::/builder\n'), (self.proc / '1/cgroup', '0::/non-systemd\n'), (self.group / 'cgroup.procs', '99\n'), (self.group / 'non-systemd/cgroup.subtree_control', 'cpu')]:
            before = path.read_text(); path.write_text(value)
            with self.assertRaises(RuntimeError): self.ensure()
            path.write_text(before)
        (self.group / 'foreign').mkdir()
        with self.assertRaises(RuntimeError): self.ensure()
        self.assertFalse(self.commands)
    def test_unknown_leaf_process_is_not_read_or_reaped(self):
        (self.group / 'non-systemd/cgroup.procs').write_text('999\n'); self.ensure()
        self.assertEqual((self.group / 'non-systemd/cgroup.procs').read_text(), '999\n'); self.assertEqual(len(self.commands), 1)
    def test_shortwrite_ioerror_and_bad_readback_fail_without_retry(self):
        for behavior in [lambda descriptor, value: 0, lambda descriptor, value: len(value)]:
            with patch.object(bridge.os, 'write', side_effect=behavior) as write:
                with self.assertRaises(RuntimeError): bridge.ensure_delegation()
                self.assertEqual(write.call_count, 1)
        with patch.object(bridge.os, 'write', side_effect=OSError('injected')) as write:
            with self.assertRaises(OSError): bridge.ensure_delegation()
            self.assertEqual(write.call_count, 1)
    def test_namespace_or_membership_changed_before_write_refused(self):
        original = bridge.cgroup_view; calls = 0
        def view(directory):
            nonlocal calls
            calls += 1
            if calls == 2: (self.proc / 'self/cgroup').write_text('0::/changed\n')
            return original(directory)
        with patch.object(bridge, 'cgroup_view', side_effect=view):
            with self.assertRaises(RuntimeError): self.ensure()
        self.assertFalse(self.commands)
    def test_failed_hello_followed_by_release_cannot_create_daemon(self):
        state = self.root / 'state'; state.mkdir(); marker = {'generationOwner': str(uuid.uuid4())}
        request = {'abi':1, 'marker':marker, 'restartAllowed':False}
        with patch.object(bridge, 'ROOT', state), patch.object(bridge, 'marker', return_value=marker), patch.object(bridge, 'ensure_delegation', side_effect=RuntimeError('partial')) as ensure, patch.object(bridge.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError, 'partial'): bridge.main(request | {'operation':'hello'})
            request.update(jobId=str(uuid.uuid4()), ownerId=str(uuid.uuid4()), descriptorSha256='a'*64)
            with contextlib.redirect_stdout(io.StringIO()): bridge.main(request | {'operation':'release'})
            self.assertEqual(ensure.call_count, 1); spawn.assert_not_called()
    def test_empty_fence_release_does_not_use_new_start_gate(self):
        state = self.root / 'state'; state.mkdir(); marker = {'generationOwner': str(uuid.uuid4())}
        request = {'abi':1, 'operation':'release', 'marker':marker, 'restartAllowed':False, 'jobId':str(uuid.uuid4()), 'ownerId':str(uuid.uuid4()), 'descriptorSha256':'a'*64}
        boot = self.root / 'boot'; boot.write_text('fixture-boot')
        bridge.write(state / 'daemon.json', {'generation':marker['generationOwner'], 'boot':'fixture-boot', 'pid':123, 'started':'456'})
        label = 'io.github.rs0325.mcicon.generation='+marker['generationOwner']
        with patch.object(bridge, 'ROOT', state), patch.object(bridge, 'BOOT', boot), patch.object(bridge, 'marker', return_value=marker), patch.object(bridge, 'process_start', return_value='456'), patch.object(bridge, 'docker', return_value=json.dumps([label])), patch.object(bridge, 'ensure_delegation') as ensure, patch.object(bridge.subprocess, 'Popen') as spawn, contextlib.redirect_stdout(io.StringIO()) as output:
            bridge.main(request)
            ensure.assert_not_called(); spawn.assert_not_called(); self.assertEqual(json.loads(output.getvalue())['actors'], 0)
    def test_init_hello_after_marker_and_failure_never_starts_daemon(self):
        state = self.root / 'state'; state.mkdir(); marker = {'generationOwner': str(uuid.uuid4())}
        request = {'abi':1, 'marker':marker, 'bridgeSha256':'fixture', 'memoryBytes':0}
        original = pathlib.Path.read_text
        def read_text(path, *args, **kwargs):
            return 'MemTotal: 16777216 kB\n' if str(path) == '/proc/meminfo' else 'fixture-boot' if path == bridge.BOOT else original(path,*args,**kwargs)
        order = []
        with patch.object(bridge, 'ROOT', state), patch.object(bridge, 'OWNER', state / 'owner.json'), patch.object(bridge, 'marker', side_effect=lambda r: order.append('marker') or marker), patch.object(bridge, 'ensure_delegation', side_effect=lambda: order.append('delegate')), patch.object(bridge, 'daemon') as daemon, patch.object(pathlib.Path, 'read_text', read_text), patch.object(bridge.os, 'cpu_count', return_value=4), contextlib.redirect_stdout(io.StringIO()):
            bridge.main(request | {'operation':'init'}); bridge.main(request | {'operation':'hello'}); daemon.assert_not_called()
        self.assertEqual(order, ['marker','delegate','marker','delegate'])
        with patch.object(bridge, 'ROOT', state), patch.object(bridge, 'marker', side_effect=RuntimeError('owner or pin')), patch.object(bridge, 'ensure_delegation') as ensure:
            with self.assertRaises(RuntimeError): bridge.main(request | {'operation':'hello'})
            ensure.assert_not_called()
        with patch.object(bridge, 'ROOT', state), patch.object(bridge, 'marker', return_value=marker), patch.object(bridge, 'ensure_delegation', side_effect=RuntimeError('partial')), patch.object(bridge, 'daemon') as daemon:
            with self.assertRaises(RuntimeError): bridge.main(request | {'operation':'hello'})
            daemon.assert_not_called()

if __name__ == '__main__': unittest.main()
