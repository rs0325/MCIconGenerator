"""実daemon/準備関数を完全stub境界で検査する。POSIX/kernelの受入ではない。"""
import ast, json, os, pathlib, subprocess, tempfile, time, types, unittest, uuid
from unittest.mock import Mock, patch

SOURCE = pathlib.Path(__file__).parents[1] / 'src/main/guest/guestbridge.py'
names = {'BridgeFailure', 'reject', 'require', 'read', 'write', 'daemon', 'identity', 'job_directory', 'prepare_daemon'}
tree = ast.parse(SOURCE.read_text(encoding='utf-8'))
bridge = types.ModuleType('daemon_fixture')
bridge.__dict__.update(os=os, sys=__import__('sys'), json=json, pathlib=pathlib, subprocess=subprocess, time=time, uuid=uuid, fcntl=types.SimpleNamespace(LOCK_EX=2, flock=Mock()), SOCKET='/run/mcicon/docker.sock')
exec(compile(ast.Module(body=[node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.ClassDef)) and node.name in names], type_ignores=[]), str(SOURCE), 'exec'), bridge.__dict__)

class DaemonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temporary.name).resolve()
        bridge.ROOT = self.root; bridge.BOOT = self.root / 'boot'; bridge.BOOT.write_text(str(uuid.uuid4()))
        self.owner = {'generationOwner': str(uuid.uuid4())}; self.attempt = str(uuid.uuid4())
        self.request = {'jobId': str(uuid.uuid4()), 'ownerId': str(uuid.uuid4()), 'descriptorSha256': 'a'*64, 'bridgeSha256': 'b'*64}
        self.request['claim'] = {'identity': bridge.identity(self.request), 'keeperAttempt': self.attempt, 'stop': {'id': str(uuid.uuid4()), 'boot': str(uuid.uuid4())}}
        self.path = bridge.job_directory(self.request)
        self.fence = {'identity': bridge.identity(self.request), 'closed': False, 'containers': [{'id': 'a'*64}], 'keeper': {'identity': bridge.identity(self.request), 'attempt': self.attempt, 'boot': bridge.BOOT.read_text(), 'pid': 100, 'started': '10'}}
        bridge.write(self.path / 'fence.json', self.fence)
        self.label = 'io.github.rs0325.mcicon.generation=' + self.owner['generationOwner']
        self.live = {100: '10', 200: '20'}
        self.child = types.SimpleNamespace(pid=200, poll=lambda: None)
        mkdir = pathlib.Path.mkdir
        self.patches = [patch.object(bridge, 'process_start', side_effect=lambda pid: self.live.get(pid), create=True), patch.object(bridge, 'docker', return_value=json.dumps([self.label]), create=True), patch.object(bridge, 'ensure_delegation', create=True), patch.object(subprocess, 'Popen', return_value=self.child), patch.object(pathlib.Path, 'mkdir', lambda path, *a, **kw: None if str(path).replace('\\', '/') == '/run/mcicon' else mkdir(path, *a, **kw))]
        for item in self.patches: item.start()
        self.spawn = subprocess.Popen
    def tearDown(self):
        for item in reversed(self.patches): item.stop()
        self.temporary.cleanup()
    def old(self, boot=None, started='19'):
        bridge.write(self.root / 'daemon.json', {'generation': self.owner['generationOwner'], 'boot': boot or bridge.BOOT.read_text(), 'pid':200, 'started':started})
    def prepare(self): return bridge.prepare_daemon(self.request, self.owner)
    def test_recovery_closed_fence_uses_confirmed_stop_once(self):
        self.fence['closed'] = True; self.fence['keeper']['stop'] = self.request['claim']['stop']; bridge.write(self.path / 'fence.json', self.fence)
        self.request['operation'] = 'daemon-recover'; self.old()
        proof = self.prepare()
        self.assertTrue(proof['ready']); self.assertEqual(self.spawn.call_count, 1)
        self.assertEqual(self.prepare(), proof); self.assertEqual(self.spawn.call_count, 1)
        self.live.pop(200)
        with self.assertRaises(RuntimeError): self.prepare()
        self.assertEqual(self.spawn.call_count, 1)
    def test_recovery_requires_closed_fence_and_stop_receipt(self):
        self.request['operation'] = 'daemon-recover'; self.old()
        with self.assertRaises(RuntimeError): self.prepare()
        self.fence['closed'] = True; self.fence['keeper']['stop'] = self.request['claim']['stop']; bridge.write(self.path / 'fence.json', self.fence)
        self.request['claim']['stop'] = None
        with self.assertRaises(RuntimeError): self.prepare()
        self.spawn.assert_not_called()
    def test_recovery_before_container_creation_never_starts_daemon(self):
        self.request['operation'] = 'daemon-recover'
        self.fence.update(closed=True, containers=[], actors=[]); self.fence['keeper']['stop'] = self.request['claim']['stop']
        bridge.write(self.path / 'fence.json', self.fence)
        self.assertTrue(self.prepare()['empty']); self.spawn.assert_not_called()
        self.fence['pendingCreate'] = 'uncertain'; bridge.write(self.path / 'fence.json', self.fence)
        with self.assertRaisesRegex(RuntimeError, 'create終端不明'): self.prepare()
        self.spawn.assert_not_called()
    def test_recovery_cannot_use_a_different_keeper_stop(self):
        self.request['operation']='daemon-recover'; self.fence['closed']=True
        self.fence['keeper']['stop']={'id':str(uuid.uuid4()),'boot':str(uuid.uuid4())}
        bridge.write(self.path / 'fence.json',self.fence)
        with self.assertRaisesRegex(RuntimeError,'停止receipt不一致'): self.prepare()
        self.spawn.assert_not_called()
    def test_same_boot_expired_pid_receipt_ack_retry_and_disappearance(self):
        self.old(); proof = self.prepare(); self.assertTrue(proof['labelVerified']); self.assertEqual(self.spawn.call_count, 1)
        self.assertEqual(self.prepare(), proof); self.assertEqual(self.spawn.call_count, 1)
        self.live.pop(200)
        with self.assertRaises(RuntimeError): self.prepare()
        self.assertEqual(self.spawn.call_count, 1); self.assertEqual(bridge.read(self.root / 'daemon-prepare.json')['proof'], proof)
    def test_missing_pid_reuse_no_receipt_label_and_generation_mismatch(self):
        for live in [None, '999']:
            self.live[200] = live; self.old()
            with self.assertRaisesRegex(RuntimeError, '停止receipt'): bridge.daemon(self.owner, False)
        self.live[200] = '19'; bridge.docker.return_value = '[]'
        with self.assertRaisesRegex(RuntimeError, 'label不一致'): bridge.daemon(self.owner, True)
        value = bridge.read(self.root / 'daemon.json'); value['generation'] = str(uuid.uuid4()); bridge.write(self.root / 'daemon.json',value)
        with self.assertRaisesRegex(RuntimeError, '世代不一致'): bridge.daemon(self.owner, True)
        self.spawn.assert_not_called()
    def test_first_creation_and_changed_boot_are_preparation_only(self):
        for previous in [None, str(uuid.uuid4())]:
            if previous: self.old(boot=previous)
            with self.assertRaisesRegex(RuntimeError, '新生成拒否'): bridge.daemon(self.owner, False, False)
            bridge.daemon(self.owner, False)
            (self.root / 'daemon.json').unlink()
        self.assertEqual(self.spawn.call_count, 2)
    def test_other_attempt_cannot_reuse_receipt_after_ack_loss(self):
        self.old(); self.prepare(); self.request['claim']['keeperAttempt'] = str(uuid.uuid4()); self.fence['keeper']['attempt'] = self.request['claim']['keeperAttempt']; bridge.write(self.path / 'fence.json',self.fence)
        with self.assertRaisesRegex(RuntimeError, '別attempt'): self.prepare()
        self.assertEqual(self.spawn.call_count, 1)
    def test_crash_before_and_after_intent_spawn_identity_and_ack(self):
        original = bridge.write
        for point in ['intent-before', 'intent-after', 'identity-before', 'identity-after', 'ack-before', 'ack-after']:
            for name in ['daemon.json', 'daemon-prepare.json']:
                (self.root / name).unlink(missing_ok=True)
            self.old(); self.spawn.reset_mock()
            def write(path, value):
                stage = 'identity' if path.name == 'daemon.json' else 'ack' if value.get('state') == 'ready' else 'intent'
                if point == stage+'-before': raise OSError('crash')
                original(path, value)
                if point == stage+'-after': raise OSError('crash')
            with patch.object(bridge, 'write', side_effect=write):
                with self.assertRaises(OSError): self.prepare()
            count = self.spawn.call_count
            if point == 'intent-before': self.prepare(); self.assertEqual(self.spawn.call_count, 1)
            elif point == 'ack-after': self.prepare(); self.assertEqual(self.spawn.call_count, count)
            else:
                with self.assertRaisesRegex(RuntimeError, '未確定'): self.prepare()
                self.assertEqual(self.spawn.call_count, count)
    def test_keeper_closed_foreign_dead_or_wrong_attempt_never_spawn(self):
        for field,value in [('closed',True), ('identity',{}), ('keeper',{})]:
            bad = self.fence | {field:value}; bridge.write(self.path / 'fence.json',bad)
            with self.assertRaises(RuntimeError): self.prepare()
        self.spawn.assert_not_called()
    def test_spawn_before_and_after_crash_never_retries_pending_intent(self):
        self.old()
        for spawned in [False, True]:
            (self.root / 'daemon-prepare.json').unlink(missing_ok=True)
            def crash(*args, **kwargs):
                if spawned: self.live[201] = '21'
                raise OSError('spawn interrupted')
            with patch.object(subprocess, 'Popen', side_effect=crash) as spawn:
                with self.assertRaises(OSError): self.prepare()
                with self.assertRaisesRegex(RuntimeError, '未確定'): self.prepare()
                self.assertEqual(spawn.call_count, 1)
    def test_failed_ready_save_never_allows_other_job_without_new_stop(self):
        self.old(); bridge.write(self.root / 'daemon-prepare.json', {'state':'pending','claim':self.request['claim']})
        self.request['claim']['stop'] = None
        with self.assertRaisesRegex(RuntimeError, '奪取拒否'): self.prepare()
        self.spawn.assert_not_called()
    def test_new_confirmed_stop_replaces_old_intent_and_consumed_permission_is_not_general(self):
        self.old(); self.prepare(); self.request['claim']['stop']['id'] = str(uuid.uuid4()); self.old(); self.prepare()
        self.assertEqual(self.spawn.call_count, 2); self.live.pop(200)
        with self.assertRaises(RuntimeError): bridge.daemon(self.owner, False, False)
        self.assertEqual(self.spawn.call_count, 2)

if __name__ == '__main__': unittest.main()
