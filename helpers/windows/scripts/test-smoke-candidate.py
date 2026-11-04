#!/usr/bin/env python3
'''実WSLを呼ばず、archive上限とcleanupの所有拒否を検査する。'''
import contextlib
import io
import hashlib
import importlib.util
import lzma
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).with_name('smoke-candidate.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeTest(unittest.TestCase):
    def test_archive_bounds(self):
        data = b'rootfs' * 100000
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'rootfs.tar.xz'
            source.write_bytes(lzma.compress(data))
            smoke.unpack(source, root / 'valid.tar', len(data), digest)
            self.assertEqual((root / 'valid.tar').read_bytes(), data)
            with self.assertRaisesRegex(RuntimeError, '上限超過'):
                smoke.unpack(source, root / 'oversize.tar', len(data) - 1, digest)
            with self.assertRaisesRegex(RuntimeError, 'pin不一致'):
                smoke.unpack(source, root / 'bad-sha.tar', len(data), '0' * 64)
            source.write_bytes(source.read_bytes() + b'extra')
            with self.assertRaisesRegex(RuntimeError, '末尾データ'):
                smoke.unpack(source, root / 'trailing.tar', len(data), digest)

    def test_cleanup_refuses_foreign_registration(self):
        value = object.__new__(smoke.Smoke)
        value.keeper = None
        value.import_attempted = True
        value.name = 'mcicon-e2-smoke-test'
        value.base = Path('H:/dedicated/distro')
        value.registration = {'name': value.name, 'basePath': str(value.base), 'version': 2, 'guid': 'expected'}
        value.facts = Mock(return_value={'distributions': [dict(value.registration, guid='replaced')]})
        value.command = Mock()
        with self.assertRaisesRegex(RuntimeError, '所有不一致'):
            value.cleanup()
        value.command.assert_not_called()

    def test_cleanup_refuses_foreign_base(self):
        value = object.__new__(smoke.Smoke)
        value.keeper = None
        value.import_attempted = True
        value.name = 'mcicon-e2-smoke-test'
        value.base = Path('H:/dedicated/distro')
        value.registration = None
        value.facts = Mock(return_value={'distributions': [{'name': value.name, 'basePath': 'C:/foreign', 'version': 2}]})
        value.command = Mock()
        with self.assertRaisesRegex(RuntimeError, '所有不一致'):
            value.cleanup()
        value.command.assert_not_called()

    def test_failed_cleanup_cannot_exit_success(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = Mock()
            fake.run = Path(directory)
            fake.result = {'errors': [], 'checks': {'g': {'passed': False}}}
            args = ['smoke-candidate.py', '--rootfs', directory, '--renderer', directory, '--helper', directory]
            with patch.object(smoke, 'Smoke', return_value=fake), patch('sys.argv', args), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(smoke.main(), 1)
            fake.execute.assert_called_once()
            fake.cleanup.assert_called_once()


if __name__ == '__main__':
    unittest.main()
