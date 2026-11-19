"""実nativeのread-only batch ABIと旧file-only ABIをWindowsで検査する。"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from native_artifact import BUILD_FLAGS, LINK_FLAGS


class NativeIdentitiesTest(unittest.TestCase):
    native = None

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='mcicon-identities-')
        self.root = pathlib.Path(self.temporary.name).resolve()
        self.file = self.root / '日本語-😀.txt'
        self.file.write_bytes(b'identity fixture')

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, request):
        if not isinstance(request, bytes):
            request = json.dumps(request, ensure_ascii=False).encode('utf-8')
        return subprocess.run([str(self.native), 'file-identities-v1'], input=request,
                              capture_output=True, timeout=10)

    def request(self, items=None):
        if items is None:
            items = [{'path': str(self.root), 'kind': 'directory'},
                     {'path': str(self.file), 'kind': 'file'}]
        return {'schemaVersion': 1, 'items': items}

    def assert_rejected(self, request):
        result = self.invoke(request)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b'')
        self.assertNotIn(str(self.root).encode(), result.stderr)

    def test_同順identityと旧file_onlyを維持する(self):
        result = self.invoke(self.request())
        self.assertEqual(result.returncode, 0, result.stderr)
        value = json.loads(result.stdout)
        self.assertEqual(set(value), {'schemaVersion', 'items'})
        self.assertEqual(value['schemaVersion'], 1)
        self.assertEqual([x['kind'] for x in value['items']], ['directory', 'file'])
        for item in value['items']:
            self.assertEqual(set(item), {'kind', 'volumeSerial', 'fileId'})
            self.assertRegex(item['volumeSerial'], r'^[0-9]{1,20}$')
            self.assertRegex(item['fileId'], r'^[0-9a-f]{32}$')
        legacy = subprocess.run([str(self.native), 'file-identity', str(self.file)],
                                capture_output=True, timeout=10)
        self.assertEqual(legacy.returncode, 0)
        expected = dict(value['items'][1])
        expected.pop('kind')
        self.assertEqual(json.loads(legacy.stdout), dict(schemaVersion=1, **expected))
        directory = subprocess.run([str(self.native), 'file-identity', str(self.root)],
                                   capture_output=True, timeout=10)
        self.assertNotEqual(directory.returncode, 0)
        self.assertEqual(directory.stdout, b'')

    def test_不明欠落重複と不正JSONは全体失敗する(self):
        valid = self.request()
        encoded = json.dumps(valid).encode()
        cases = [b'', b'\xff', encoded + b' x', encoded.replace(b'"schemaVersion": 1', b'"schemaVersion": 1.0'),
                 encoded.replace(b'"schemaVersion": 1', b'"schemaVersion": 1, "schemaVersion": 1'),
                 dict(valid, unknown=0), {'schemaVersion': 2, 'items': valid['items']},
                 {'items': valid['items']}, {'schemaVersion': 1}, self.request([]),
                 self.request([valid['items'][0]] * 2),
                 self.request([dict(valid['items'][1], unknown='value')]),
                 self.request([{'path': str(self.file)}]),
                 self.request([{'kind': 'file'}]),
                 self.request([{'path': 'relative.txt', 'kind': 'file'}]),
                 self.request([{'path': str(self.file), 'kind': 'unknown'}]),
                 self.request([{'path': str(self.file) + '\x00', 'kind': 'file'}]),
                 b' ' * (4 * 1024 * 1024 + 1)]
        for case in cases:
            with self.subTest(case=str(case)[:140]):
                self.assert_rejected(case)

    def test_欠落とkind不一致は部分応答しない(self):
        for item in [{'path': str(self.root / 'missing'), 'kind': 'file'},
                     {'path': str(self.root), 'kind': 'file'},
                     {'path': str(self.file), 'kind': 'directory'}]:
            with self.subTest(item=item):
                self.assert_rejected(self.request([self.request()['items'][1], item]))

    def test_hardlinkを新旧両ABIで拒否する(self):
        link = self.root / 'hardlink'
        os.link(self.file, link)
        self.assert_rejected(self.request())
        legacy = subprocess.run([str(self.native), 'file-identity', str(self.file)],
                                capture_output=True, timeout=10)
        self.assertNotEqual(legacy.returncode, 0)
        self.assertEqual(legacy.stdout, b'')

    def test_別表記の同じ実体とreparse_directoryを拒否する(self):
        items = self.request()['items']
        self.assert_rejected(self.request([items[1], {'path': self.file.as_posix(), 'kind': 'file'}]))
        junction = self.root / 'junction'
        command = pathlib.Path(os.environ['SystemRoot']) / 'System32/cmd.exe'
        result = subprocess.run([str(command), '/d', '/c', 'mklink', '/J', str(junction), str(self.root)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        try:
            self.assert_rejected(self.request([{'path': str(junction), 'kind': 'directory'}]))
        finally:
            os.rmdir(junction)
        self.assertTrue(self.file.is_file())

    def test_256件を受理し257件を拒否する(self):
        items = []
        for number in range(257):
            file = self.root / str(number)
            file.write_bytes(b'fixture')
            items.append({'path': str(file), 'kind': 'file'})
        result = self.invoke(self.request(items[:256]))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads(result.stdout)['items']), 256)
        self.assertLessEqual(len(result.stdout), 65536)
        self.assert_rejected(self.request(items))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', required=True, type=pathlib.Path)
    parser.add_argument('--cxx', type=pathlib.Path)
    parser.add_argument('--wait-fixture-output', type=pathlib.Path)
    args, remaining = parser.parse_known_args()
    if sys.platform != 'win32':
        parser.error('Windows実native専用です')
    NativeIdentitiesTest.native = args.native.resolve(strict=True)
    if bool(args.cxx) != bool(args.wait_fixture_output):
        parser.error('--cxxと--wait-fixture-outputは両方指定してください')
    if args.cxx:
        output = args.wait_fixture_output.resolve()
        if output.exists():
            parser.error('既存の待機fixtureの上書きはできません')
        source = pathlib.Path(__file__).resolve().parents[1] / 'src/test/native/identity-wait-fixture.cpp'
        subprocess.run([str(args.cxx.resolve(strict=True)), *BUILD_FLAGS, str(source), '-o', str(output), *LINK_FLAGS], check=True)
    unittest.main(argv=[sys.argv[0], *remaining])
