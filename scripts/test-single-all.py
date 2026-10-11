"""個別/all比較が誤った画像・入力・パス・再利用結果を成功としないことを確認する。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('single_all', Path(__file__).with_name('verify-single-all.py'))
single_all = importlib.util.module_from_spec(spec)
spec.loader.exec_module(single_all)


def png(color):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(b'\x00' + bytes(color))) + chunk(b'IEND', b''))


class SingleAllTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.item = 'minecraft:stone'
        self.path = 'assets/minecraft/textures/block/stone.png'
        self.baseline = self.write('all')
        self.single = self.write('single')

    def write(self, name, color=(100, 80, 60, 255), path=None):
        path = path or self.path
        base = self.root / name
        image = base / path
        image.parent.mkdir(parents=True)
        data = png(color)
        image.write_bytes(data)
        manifest = {'version': '1.21.4', 'mode': 'icons', 'complete': True,
                    'sourceSha256': 'client', 'settingsDigest': 'settings', 'settings': {'size': 1},
                    'renderer': 'native', 'context': {'profile': 'fixed-v1'}, 'environment': {'imageId': 'image'},
                    'catalog': [{'id': self.item, 'kind': 'block', 'status': 'available'}],
                    'counts': {'succeeded': 1, 'failed': 0, 'noItemForm': 0},
                    'entries': [{'id': self.item, 'path': path, 'status': 'success',
                                 'sha256': hashlib.sha256(data).hexdigest()}]}
        result = base / 'manifest.json'
        result.write_text(json.dumps(manifest))
        return result

    def change(self, key, value):
        manifest = json.loads(self.single.read_text())
        manifest[key] = value
        self.single.write_text(json.dumps(manifest))

    def verify(self):
        return single_all.verify_single(self.single, self.baseline, self.item)

    def test_accepts_exact_image_and_path(self):
        self.assertTrue(self.verify()['comparisonVerified'])

    def test_rejects_one_channel_difference_even_with_valid_hash(self):
        self.single = self.write('different', color=(101, 80, 60, 255))
        with self.assertRaisesRegex(ValueError, 'Decoded RGBA differs'):
            self.verify()

    def test_rejects_wrong_path_even_with_same_rgba(self):
        self.single = self.write('other-path', path='assets/minecraft/textures/item/stone.png')
        with self.assertRaisesRegex(ValueError, 'matching successful entry'):
            self.verify()

    def test_rejects_different_inputs_settings_and_environment(self):
        for key in ('sourceSha256', 'settings', 'settingsDigest', 'renderer', 'context', 'environment', 'catalog'):
            with self.subTest(key=key):
                original = self.single.read_text()
                self.change(key, 'changed')
                with self.assertRaisesRegex(ValueError, key):
                    self.verify()
                self.single.write_text(original)

    def test_rejects_more_than_one_entry(self):
        manifest = json.loads(self.single.read_text())
        self.change('entries', manifest['entries'] * 2)
        with self.assertRaisesRegex(ValueError, '1件だけ'):
            self.verify()

    def test_rejects_other_selected_id(self):
        with self.assertRaisesRegex(ValueError, 'ID'):
            single_all.verify_single(self.single, self.baseline, 'minecraft:diamond')

    def test_rejects_replaced_png(self):
        (self.single.parent / self.path).write_bytes(png((101, 80, 60, 255)))
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
