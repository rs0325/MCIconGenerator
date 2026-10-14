import importlib.util
from pathlib import Path
import json
import subprocess
import tempfile
import unittest

source = Path(__file__).with_name('owned_container.py')
spec = importlib.util.spec_from_file_location('owned_container', source)
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)


class FakeDocker:
    def __init__(self, container):
        self.container = container
        self.removed = False
        self.rm_calls = 0

    def __call__(self, command, **kwargs):
        if 'run' in command:
            raise subprocess.TimeoutExpired(command, kwargs.get('timeout'))
        if 'inspect' in command:
            if self.removed:
                return subprocess.CompletedProcess(command, 1, '', 'not found')
            return subprocess.CompletedProcess(command, 0, json.dumps([self.container]), '')
        if '--force' in command:
            self.rm_calls += 1
            self.removed = True
            return subprocess.CompletedProcess(command, 0, 'container-id', '')
        raise AssertionError(command)


class OwnedContainerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.mounts = {
            '/mcicon/cache': (str(root / 'cache'), False),
            '/mcicon/qa': (str(root / 'out'), True),
            '/mcicon/control': (str(root / 'control'), False),
        }
        self.name = 'mcicon-world-qa-uninstrumented-uuid'
        self.container = {
            'Name': '/' + self.name,
            'Config': {'Labels': {'mcicon.qa.owner': self.name}},
            'Mounts': [
                {'Source': source, 'Destination': destination, 'RW': writable}
                for destination, (source, writable) in self.mounts.items()
            ],
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_removes_owned_container_created_before_run_response_timeout(self):
        docker = FakeDocker(self.container)
        with self.assertRaises(subprocess.TimeoutExpired):
            docker(['docker', 'run'], timeout=1)
        result = owned.cleanup(['docker'], self.name, self.name, self.mounts, run=docker)
        self.assertTrue(result['removed'])
        self.assertEqual(docker.rm_calls, 1)

    def test_preserves_unowned_container_with_same_name(self):
        foreign = dict(self.container)
        foreign['Config'] = {'Labels': {'mcicon.qa.owner': 'different-run'}}
        docker = FakeDocker(foreign)
        result = owned.cleanup(['docker'], self.name, self.name, self.mounts, run=docker)
        self.assertFalse(result['removed'])
        self.assertFalse(result['owned'])
        self.assertEqual(docker.rm_calls, 0)
        self.assertFalse(docker.removed)
