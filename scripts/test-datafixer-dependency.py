#!/usr/bin/env python3
"""保留観測の所有照合、dumpと解除の順、異常時解除をゲームなしで検証する。"""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dependency', Path(__file__).with_name('verify-datafixer-dependency.py'))
dependency = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dependency)


class DependencyTest(unittest.TestCase):
    def test_cleanup_and_game_failures_keep_observer_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            bundle = root / 'bundle'
            cache = bundle / 'cache'
            cache.mkdir(parents=True)
            (bundle / 'reference').mkdir()
            (bundle / 'reference/manifest.json').write_text(json.dumps({'entries': [{'id': 'minecraft:shield', 'path': 'shield.png'}]}))
            (bundle / 'reference/shield.png').write_bytes(b'fixture')
            (bundle / 'input-freeze.json').write_text(json.dumps({'files': [], 'referenceFiles': {}, 'distributionFilesSha256': {}}))
            agent = root / 'agent.jar'
            agent.write_bytes(b'fixture agent')
            result = root / 'result'
            gate_text = 'agent.ready\t1\t1\ngate.waiting\t2\t2\ngate.expired\t3\t2\n'

            def process(command, **kwargs):
                folder = Path(command[command.index('--out') + 1])
                (folder / '0/output/java/1.21.4/icons').mkdir(parents=True)
                (folder / '0/output/java/1.21.4/icons/manifest.json').write_text('{}')
                (folder / '0/output/java/1.21.4/icons/shield.png').write_bytes(b'fixture')
                run = cache / ('worker-' + folder.name)
                run.mkdir()
                life = {'runDirectory': str(run), 'stageAgentSha256': dependency.hashlib.sha256(agent.read_bytes()).hexdigest()}
                (folder / '0/docker-run.json').write_text(json.dumps(life))
                if folder.name == 'gated': (run / 'datafixer-gate.tsv').write_text(gate_text)
                detail = {'jobs': [{'passed': folder.name == 'control', 'lifecycle': life}],
                          'coreSha256': 'eec62d03a0f004e4d3d4963eba31b399ace8b030135651c1480f4c40a92db825',
                          'workerSha256': '8b85e11a940ef3497937e0af1d9f3571e2ba2842a6cd2b11dafba027805acb42',
                          'sourceSha256': 'c17c450c6e72cc51297daa57ce38f800aa01cf022b743daa21a0512d326d894e', 'imageId': 'fixture image'}
                (folder / 'report.json').write_text(json.dumps(detail))
                return SimpleNamespace(returncode=0)

            def cleanup(folder, *_):
                if folder.name == 'gated': raise RuntimeError('fixture cleanup failure')
                return []

            class Observer:
                def __init__(self, target, args): self.result = args[-1]
                def start(self): self.result.update(failure='fixture gate expired', stagesAtDumpRequest='fixture held snapshot')
                def join(self, timeout): pass
                def is_alive(self): return False

            soak = SimpleNamespace(IMAGE='fixture image', wait_unreaped=lambda *_: None,
                                   reap_group=lambda _: {'forced': False, 'remaining': [], 'exitCode': 0}, cleanup_containers=cleanup)
            stages = SimpleNamespace(read_stages=lambda _: {}, compare_metadata=lambda *_: 14)
            argv = ['verify-datafixer-dependency.py', '--bundle', str(bundle), '--stage-agent', str(agent), '--gate-agent', str(agent), '--out', str(result)]
            with patch('sys.argv', argv), patch.object(dependency, 'load', side_effect=[stages, soak]), patch.object(dependency.subprocess, 'Popen', process), patch.object(dependency.threading, 'Thread', Observer), patch.object(dependency.signal, 'signal'):
                self.assertEqual(1, dependency.main())
            report = json.loads((result / 'report.json').read_text())
            gated = report['jobs'][1]
            self.assertFalse(report['passed'])
            self.assertFalse(gated['passed'])
            self.assertIn('fixture cleanup failure', gated['containerCleanupFailure'])
            self.assertEqual('fixture held snapshot', gated['gateObservation']['stagesAtDumpRequest'])
            self.assertEqual(gate_text, (result / 'gated/datafixer-gate.tsv').read_text())

    def test_dump_precedes_release_and_wrong_mount_is_not_probed(self):
        for wrong_mount, late_join in [(False, False), (True, False), (False, True)]:
            with self.subTest(wrong_mount=wrong_mount, late_join=late_join), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                cache = root / 'cache'
                run = cache / 'worker-fixture'
                run.mkdir(parents=True)
                folder = root / 'job'
                (folder / '0').mkdir(parents=True)
                (folder / '0/docker-run.json').write_text(json.dumps({'containerName': 'owned-fixture', 'runDirectory': str(run)}))
                (run / 'startup-stages.tsv').write_text('' if late_join else 'join.before\t1\t1\n')
                (run / 'datafixer-gate.tsv').write_text('gate.waiting\t1\t2\n')
                info = {'Id': 'owned-id', 'Name': '/owned-fixture', 'Image': 'sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf',
                        'HostConfig': {'NetworkMode': 'none'}, 'Mounts': [
                            {'Destination': '/mcicon/cache', 'Source': '/wrong' if wrong_mount else str(cache), 'RW': True},
                            {'Destination': '/mcicon/worker.jar', 'Source': str(run / 'worker-agent.jar'), 'RW': False}]}
                calls = []
                def fake(command, **kwargs):
                    calls.append(command)
                    self.assertFalse((run / 'datafixer-release').exists())
                    if command[1] == 'inspect':
                        if late_join: (run / 'startup-stages.tsv').write_text('join.before\t1\t1\n')
                        return subprocess.CompletedProcess(command, 0, json.dumps([info]), '')
                    self.assertEqual(['docker', 'exec', 'owned-id', '/opt/java/java21/bin/jcmd', '1', 'Thread.print'], command)
                    kwargs['stdout'].write(b'fixture thread dump\n')
                    return subprocess.CompletedProcess(command, 0)
                result = {}
                with patch.object(dependency.subprocess, 'run', fake), patch.object(dependency.time, 'monotonic', side_effect=[0, 1, 2, 18]):
                    dependency.observe_gate(folder, cache, ['docker'], threading.Event(), result)
                self.assertTrue((run / 'datafixer-release').exists())
                if wrong_mount:
                    self.assertIn('failure', result)
                    self.assertEqual(1, len(calls))
                else:
                    self.assertNotIn('failure', result)
                    self.assertEqual(not late_join, result['joinObservedAtDumpRequest'])
                    self.assertTrue(result['joinObservedInLastHeldSnapshot'])
                    self.assertLessEqual(result['dumpFinishedHostMonotonicNs'], result['releaseHostMonotonicNs'])
                    self.assertEqual(2, len(calls))


if __name__ == '__main__':
    unittest.main()
