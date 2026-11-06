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
        with self.assertRaisesRegex(ValueError, '採用baseline'):
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

    def test_patch_version_reuses_original_basename_and_per_artifact_url(self):
        import json
        plan = copy.deepcopy(self.plan)
        plan.update(version='1.1.1', tag='v1.1.1')
        for row in plan['artifacts']:
            if row['role'] == 'host-helper' or row['host'] == 'windows-x86_64':
                row['id'] = row['id'].replace('1.1.0', '1.1.1')
                changed = self.root / row['id']
                changed.write_bytes(b'new-1.1.1-baseline')
                row['runs'] = [str(changed), str(changed)]
                row['baseline'] = {'bytes': changed.stat().st_size, 'sha256': candidate.digest(changed)}
            else:
                fmt = 'qcow2' if row['role'] == 'guest-base' else 'tar.gz'
                row['basename'] = row['id'] + '.' + fmt
        renderer = plan['artifacts'][-1]
        renderer['url'] = 'https://example.com/reused/' + renderer['basename']
        report = self.assemble(plan)
        for target in report['signingTargets']:
            descriptor = json.loads((self.root / 'output' / target['path']).read_bytes())
            self.assertEqual(descriptor['toolVersion'], '1.1.1')
            self.assertEqual(descriptor['worker']['toolVersion'], '1.1.1')
            self.assertTrue(descriptor['runtimeId'].startswith('mcicon-1.1.1-'))
            for artifact in descriptor['artifacts']:
                inventory = self.root / 'output' / artifact['inventoryPath']
                self.assertEqual(candidate.digest(inventory), artifact['inventorySha256'])
                self.assertEqual(json.loads(inventory.read_bytes())['artifact']['url'], artifact['url'])
                self.assertEqual(artifact['url'], renderer['url'] if artifact['role'] == 'renderer-image'
                                 else 'https://github.com/rs0325/MCIconGenerator/releases/download/v1.1.1/' + artifact['id'] + '.' + artifact['format'])
        self.assertTrue((self.root / 'output/artifacts' / renderer['basename']).is_file())

    def test_rejects_invalid_release_and_insecure_artifact_url_before_output(self):
        for field, value in [('version', '../1.1.1'), ('tag', '../v1.1.1')]:
            plan = copy.deepcopy(self.plan)
            plan[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.assemble(plan)
            self.assertFalse((self.root / 'output').exists())
        plan = copy.deepcopy(self.plan)
        plan['artifacts'][0]['url'] = 'http://example.com/helper.zip'
        with self.assertRaisesRegex(ValueError, 'HTTPS'):
            self.assemble(plan)
        self.assertFalse((self.root / 'output').exists())

    def test_final_adoption_requires_closure_and_propagates_without_signing(self):
        import json
        plan = copy.deepcopy(self.plan)
        plan['finalBytesConfirmed'] = True
        with self.assertRaisesRegex(ValueError, '閉包'):
            self.assemble(plan)
        self.assertFalse((self.root / 'output').exists())
        plan['unresolved'] = []
        for row in plan['artifacts']:
            row['contents']['licenseClosureConfirmed'] = True
            row['noticeFiles'] = ['NOTICE']
            row['sourceArchives'] = [{'url': 'https://example.com/sources.tar', 'sha256': '1' * 64}]
        for field in ('licenseClosureConfirmed', 'noticeFiles', 'sourceArchives'):
            with self.subTest(field=field):
                incomplete = copy.deepcopy(plan)
                row = incomplete['artifacts'][0]
                if field == 'licenseClosureConfirmed':
                    row['contents'][field] = False
                else:
                    row[field] = []
                with self.assertRaisesRegex(ValueError, '閉包'):
                    self.assemble(incomplete)
                self.assertFalse((self.root / 'output').exists())
        report = self.assemble(plan)
        self.assertTrue(report['finalBytesConfirmed'])
        self.assertTrue(json.loads((self.root / 'output/input.json').read_bytes())['finalBytesConfirmed'])
        for row in report['artifacts']:
            inventory = self.root / 'output' / row['inventory']['path']
            self.assertTrue(json.loads(inventory.read_bytes())['finalBytesConfirmed'])
        self.assertFalse(list((self.root / 'output').rglob('*.sig')))


if __name__ == '__main__':
    unittest.main()
