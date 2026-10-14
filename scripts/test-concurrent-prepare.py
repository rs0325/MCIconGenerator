"""準備失敗・入力の変化・残留partを成功としない回帰検査。"""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('verify-concurrent-prepare.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareReportTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {'sourceSha256': 'source', 'natives': {'native': 'hash'}}
        self.before = {'files': {'input': 'hash'}, 'parts': []}
        self.result = {'exitCode': 0, 'prepared': self.baseline}

    def test_process_failure(self):
        self.assertTrue(prepare.evaluate(self.result, self.baseline, self.before, self.before))
        for code in (1, None):
            with self.subTest(code=code):
                self.assertFalse(prepare.evaluate(dict(self.result, exitCode=code),
                                                 self.baseline, self.before, self.before))

    def test_derived_input_mismatch(self):
        for prepared in (None, {'sourceSha256': 'different'}):
            with self.subTest(prepared=prepared):
                self.assertFalse(prepare.evaluate(dict(self.result, prepared=prepared),
                                                 self.baseline, self.before, self.before))

    def test_changed_cache_and_residual_part(self):
        for after in [{'files': {}, 'parts': []}, {'files': {'input': 'different'}, 'parts': []},
                      {'files': self.before['files'], 'parts': [{'path': '.mcicon-1.part', 'bytes': 1}]}]:
            with self.subTest(after=after):
                self.assertFalse(prepare.evaluate(self.result, self.baseline, self.before, after))

    def test_snapshot_separates_parts_and_locks(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            (cache / 'input').write_bytes(b'input')
            (cache / 'input.lock').touch()
            (cache / '.mcicon-1.part').write_bytes(b'part')
            first = prepare.snapshot(cache)
            self.assertEqual(set(first['files']), {'input'})
            self.assertEqual(first['parts'], [{'path': '.mcicon-1.part', 'bytes': 4}])
            (cache / 'input').write_bytes(b'changed')
            self.assertNotEqual(first['files'], prepare.snapshot(cache)['files'])


if __name__ == '__main__':
    unittest.main()
