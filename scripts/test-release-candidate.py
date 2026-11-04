"""候補組立てのpin不一致・共有資材・出力不変性を検証する。"""
import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('candidate', Path(__file__).with_name('assemble-release-candidate.py'))
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)


class CandidateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'adapters').mkdir()
        for name in ('legacy-mappings.json', 'releases.json'):
            (self.root / 'adapters' / name).write_bytes(b'{}')
        source = self.root / 'source'
        source.write_bytes(b'candidate-test')
        pin = {'bytes': source.stat().st_size, 'sha256': candidate.digest(source)}
        artifacts = []
        for role, host in [('host-helper', 'windows-x86_64'), ('host-helper', 'macos-aarch64'),
                           ('guest-base', 'windows-x86_64'), ('guest-base', 'macos-aarch64'),
                           ('renderer-image', 'linux-amd64')]:
            kind = {'host-helper': 'helper', 'guest-base': 'guest', 'renderer-image': 'renderer'}[role]
            artifacts.append({'id': 'mcicon-' + kind + '-1.1.0-' + host, 'role': role, 'host': host,
                              'runs': [str(source), str(source)], 'baseline': pin,
                              'unpackedBytes': 1024, 'licenseFiles': ['LICENSE'], 'noticeFiles': [],
                              'sourceArchives': [], 'contents': {}, 'evidence': []})
        self.plan = {'version': '1.1.0', 'tag': 'v1.1.0', 'agent': str(source),
                     'artifacts': artifacts, 'rendering': {}, 'unresolved': ['署名待ち']}

    def assemble(self, plan=None, name='output'):
        return candidate.assemble(self.plan if plan is None else plan, self.root / name, self.root)

    def test_deterministic_shared_renderer_and_unsigned_input(self):
        a = self.assemble()
        b = self.assemble(name='other')
        self.assertEqual(a['signingTargets'], b['signingTargets'])
        self.assertFalse(a['finalBytesConfirmed'])
        import json
        hosts = [json.loads((self.root / 'output' / row['path']).read_bytes()) for row in a['signingTargets']]
        left, right = (d['artifacts'][2] for d in hosts)
        self.assertNotEqual(left['inventoryPath'], right['inventoryPath'])
        self.assertEqual({k: v for k, v in left.items() if k != 'inventoryPath'},
                         {k: v for k, v in right.items() if k != 'inventoryPath'})
        self.assertEqual(len(set(a['inventoryResourcePaths'])), 6)
        self.assertEqual((self.root / 'output' / left['inventoryPath']).read_bytes(),
                         (self.root / 'output' / right['inventoryPath']).read_bytes())
        self.assertTrue(all('/v1.1.0/mcicon-' in r['url'] for d in hosts for r in d['artifacts']))
        self.assertFalse(list((self.root / 'output').rglob('*.sig')))
        with self.assertRaises(ValueError):
            self.assemble()

    def test_rejects_baseline_and_second_run_mismatch_before_output(self):
        plan = copy.deepcopy(self.plan)
        plan['artifacts'][0]['baseline']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'QA pin'):
            self.assemble(plan)
        self.assertFalse((self.root / 'output').exists())
        plan = copy.deepcopy(self.plan)
        changed = self.root / 'changed'
        changed.write_bytes(b'changed')
        plan['artifacts'][0]['runs'][1] = str(changed)
        with self.assertRaisesRegex(ValueError, '再生成2回'):
            self.assemble(plan)

    def test_rejects_missing_host_and_colliding_basename(self):
        plan = copy.deepcopy(self.plan)
        plan['artifacts'].pop()
        with self.assertRaises(ValueError):
            self.assemble(plan)
        plan = copy.deepcopy(self.plan)
        plan['artifacts'][1]['id'] = plan['artifacts'][0]['id']
        with self.assertRaises(ValueError):
            self.assemble(plan)


if __name__ == '__main__':
    unittest.main()
