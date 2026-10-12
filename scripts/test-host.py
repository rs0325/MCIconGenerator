#!/usr/bin/env python3
"""ホストQAが集合欠落・異なる入力・未完了・監査例外を成功扱いしないことを確認する。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
import zlib

spec = importlib.util.spec_from_file_location('host', Path(__file__).with_name('verify-host.py'))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


class HostTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'client.jar'
        def chunk(kind, body):
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0))
        png += chunk(b'IDAT', zlib.compress(b'\0\x78\x50\x28\xff')) + chunk(b'IEND', b'')
        self.data = {'version': '1.13', 'mode': 'icons', 'complete': True, 'settings': {'size': 1}, 'settingsDigest': 'same-digest',
                     'context': {'profile': 'fixed-v1'}, 'environment': {'os': 'linux'}, 'renderer': 'native',
                     'counts': {'succeeded': 2, 'failed': 0, 'noItemForm': 0}, 'catalog': [], 'entries': [], 'note': '透過画像の検証'}
        with zipfile.ZipFile(self.source, 'w') as archive:
            for name, kind in [('stone', 'block'), ('wheat', 'item')]:
                path = 'assets/minecraft/textures/' + kind + '/' + name + '.png'
                archive.writestr(path, png)
                target = self.root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(png)
                self.data['catalog'].append({'id': 'minecraft:' + name, 'kind': kind, 'status': 'available', 'blocks': []})
                self.data['entries'].append({'id': 'minecraft:' + name, 'path': path, 'status': 'success',
                                             'sha256': hashlib.sha256(png).hexdigest(), 'sourceSha256': hashlib.sha256(png).hexdigest(), 'auxiliary': {}})
            archive.writestr('assets/minecraft/textures/entity/other.png', png)
        self.data['sourceSha256'] = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.baseline = self.root / 'baseline.json'
        self.manifest = self.root / 'manifest.json'
        self.baseline.write_text(json.dumps(self.data, ensure_ascii=False), encoding='utf-8')

    def verify(self, ids=None, raw=False, require_renderer=True):
        self.manifest.write_text(json.dumps(self.data, ensure_ascii=False), encoding='utf-8')
        return host.verify_output(self.manifest, self.source, None if raw else self.baseline, ids, require_renderer)

    def test_accepts_exact_population_and_utf8(self):
        self.assertTrue(self.verify()['passed'])
        self.assertTrue(self.verify(['minecraft:stone', 'minecraft:wheat'])['passed'])

    def test_repeat_rejects_missing_ids_including_empty_output(self):
        for count in [1, 0]:
            self.data['entries'] = self.data['entries'][:count]
            self.data['counts']['succeeded'] = count
            self.assertFalse(self.verify(['minecraft:stone', 'minecraft:wheat'])['passed'])

    def test_all_rejects_catalog_and_output_missing_together(self):
        self.data['entries'].pop()
        self.data['catalog'].pop()
        self.data['counts']['succeeded'] = 1
        self.assertFalse(self.verify()['passed'])

    def test_cross_renderer_still_requires_matching_inputs_and_rgba(self):
        self.data['renderer'] = 'different SIMD width'
        self.assertFalse(self.verify()['passed'])
        result = self.verify(require_renderer=False)
        self.assertTrue(result['passed'])
        self.assertFalse(result['rendererEqual'])
        self.assertFalse(result['rendererRequired'])
        self.data['settingsDigest'] = 'different-digest'
        self.assertFalse(self.verify(require_renderer=False)['passed'])
        self.data['settingsDigest'] = 'same-digest'
        self.data['settings']['size'] = 2
        self.assertFalse(self.verify(require_renderer=False)['passed'])
        self.data['settings']['size'] = 1
        image = self.root / self.data['entries'][0]['path']
        image.write_bytes(b'invalid PNG')
        self.assertFalse(self.verify(require_renderer=False)['passed'])

    def test_raw_requires_all_item_and_block_textures(self):
        self.data['mode'] = 'raw'
        for entry in self.data['entries']:
            entry['id'] = 'minecraft:' + entry['path'].split('/textures/')[1][:-4]
        self.assertTrue(self.verify(raw=True)['passed'])
        self.data['entries'].pop()
        self.data['counts']['succeeded'] = 1
        self.assertFalse(self.verify(raw=True)['passed'])

    def test_rejects_incomplete_and_different_source(self):
        for key, value in [('complete', False), ('sourceSha256', 'other')]:
            original = self.data[key]
            self.data[key] = value
            self.assertFalse(self.verify()['passed'])
            self.data[key] = original

    def test_audit_exception_is_saved_as_failed_job(self):
        distribution = self.root / 'cli'
        (distribution / 'lib').mkdir(parents=True)
        (distribution / 'worker').mkdir()
        (distribution / 'lib/core-test.jar').write_bytes(b'core')
        (distribution / 'worker/worker-agent.jar').write_bytes(b'worker')
        output = self.root / 'output'
        def run(command, **kwargs):
            if command[:2] == ['java', '-version']:
                return SimpleNamespace(stderr='java 21')
            if command[0] == 'docker':
                return SimpleNamespace(stdout='{}')
            folder = Path(command[command.index('--out') + 1]) / 'java/1.13' / ('raw' if 'extract' in command else 'icons')
            folder.mkdir(parents=True)
            (folder / 'manifest.json').write_text('{', encoding='utf-8')
            return SimpleNamespace(returncode=0)
        args = ['verify-host.py', '--out', str(output), '--distribution', str(distribution),
                '--cache', str(self.root), '--compare', str(self.root), '--versions', '1.13']
        with patch('sys.argv', args), patch.object(host.subprocess, 'run', side_effect=run):
            self.assertTrue(host.main())
        report = json.loads((output / 'report.json').read_text(encoding='utf-8'))
        self.assertEqual(3, len(report['jobs']))
        self.assertFalse(report['passed'])
        self.assertTrue(all(not j['passed'] and j['error'].startswith('JSONDecodeError:') for j in report['jobs']))


if __name__ == '__main__':
    unittest.main()
