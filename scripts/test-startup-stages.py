#!/usr/bin/env python3
"""境界の欠落・重複・別thread・逆行を成功と判定しないことを確認する。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('stages', Path(__file__).with_name('verify-startup-stages.py'))
stages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stages)


class StageTest(unittest.TestCase):
    def test_all_fixed_metadata_fields_are_required_and_equal(self):
        reference = {key: key for key in stages.METADATA}
        self.assertEqual(14, stages.compare_metadata(dict(reference), reference))
        for key in stages.METADATA:
            with self.subTest(key=key):
                changed = dict(reference); changed[key] = None
                with self.assertRaises(ValueError): stages.compare_metadata(changed, reference)
                del changed[key]
                with self.assertRaises(ValueError): stages.compare_metadata(changed, reference)

    def test_complete_and_invalid_observations(self):
        expected = [name + '\t' + str(i * 1000000) + '\t1' for i, name in enumerate(stages.STAGES)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'stages.tsv'
            path.write_text('\n'.join(expected) + '\n')
            self.assertEqual(1, stages.read_stages(path)['clientMillis'])
            cases = [expected[:-1], expected + expected[-1:], expected[:3] + expected[4:],
                     expected[:5] + [expected[6], expected[5]] + expected[7:]]
            thread = list(expected);thread[-1] = thread[-1][:-1] + '2';cases.append(thread)
            backwards = list(expected);backwards[-1] = 'client.after\t1\t1';cases.append(backwards)
            for rows in cases:
                with self.subTest(rows=rows):
                    path.write_text('\n'.join(rows) + '\n')
                    with self.assertRaises(ValueError): stages.read_stages(path)


if __name__ == '__main__':
    unittest.main()
