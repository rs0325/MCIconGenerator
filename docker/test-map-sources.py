"""source索引の解釈とbinary対応を、小さな固定fixtureで検証する。"""

import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).parent
spec = importlib.util.spec_from_file_location('mapping', ROOT / 'map-sources.py')
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)
spec = importlib.util.spec_from_file_location('metadata', ROOT / 'fetch-source-metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)

FIXTURE = ROOT / 'tests' / 'fixtures' / 'Sources'


class SourceTests(unittest.TestCase):
    def setUp(self):
        self.fields = list(mapping.paragraphs(FIXTURE.read_text(encoding='utf-8')))
        self.sources = {(f['Package'], f['Version']): mapping.source_record(f) for f in self.fields}

    def test_continuations_and_all_source_members(self):
        source = self.sources[('sample-source', '1:2.0-1')]
        self.assertEqual(['libsample1', 'sample-tools'], source['binaryNames'])
        self.assertEqual('4.7.0', source['standardsVersion'])
        self.assertEqual(2, len(source['sourceArchives']))
        self.assertEqual(3, len(source['files']))
        self.assertEqual('a' * 64, source['dsc']['sha256'])
        self.assertTrue(source['dsc']['url'].endswith('pool/main/s/sample-source/sample-source_2.0-1.dsc'))
        self.assertEqual(12, source['sourceArchives'][0]['bytes'])

    def test_source_field_and_binary_nmu(self):
        package = {'package': 'libsample1', 'version': '1:2.0-1+b2',
                   'sourceField': 'sample-source (1:2.0-1)'}
        result = mapping.match_packages([package], self.sources)[0]
        self.assertEqual('sample-source@1:2.0-1', result['sourceId'])
        self.assertEqual('1:2.0-1+b2', result['version'])

    def test_source_name_without_version_and_absent_field(self):
        self.assertEqual(('sample-source', '1:2.0-1'), mapping.source_identity(
            {'package': 'sample-tools', 'version': '1:2.0-1', 'sourceField': 'sample-source'}))
        result = mapping.match_packages(
            [{'package': 'native', 'version': '3.0', 'sourceField': None}], self.sources)[0]
        self.assertEqual('native@3.0', result['sourceId'])

    def test_missing_version_is_not_replaced_with_other_version(self):
        result = mapping.match_packages(
            [{'package': 'libsample1', 'version': '1:2.0-2', 'sourceField': 'sample-source'}], self.sources)[0]
        self.assertIsNone(result['sourceId'])
        self.assertIn('完全一致', result['unmatchedReason'])

    def test_binary_must_belong_to_source(self):
        result = mapping.match_packages(
            [{'package': 'unrelated', 'version': '1:2.0-1', 'sourceField': 'sample-source'}], self.sources)[0]
        self.assertIsNone(result['sourceId'])
        self.assertIn('Binary', result['unmatchedReason'])

    def test_native_archive_and_signed_dsc(self):
        source = self.sources[('native', '3.0')]
        self.assertEqual(1, len(source['sourceArchives']))
        text = ('-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n'
                'Source: native\nVersion: 3.0\n- -escaped: value\n'
                '-----BEGIN PGP SIGNATURE-----\nfixture\n')
        fields = metadata.dsc_fields(text.encode())
        self.assertEqual('native', fields['Source'])
        self.assertEqual('value', fields['-escaped'])

    def test_malformed_source_and_checksum_are_rejected(self):
        with self.assertRaises(ValueError):
            mapping.source_identity({'package': 'x', 'version': '1', 'sourceField': 'broken (x'})
        with self.assertRaises(ValueError):
            mapping.source_record({**self.fields[0], 'Checksums-Sha256': 'bad 12 a.tar.xz'})

    def test_full_generation_is_offline_and_checks_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cache = root / 'indexes'
            cache.mkdir()
            for suite in mapping.SUITES:
                for component in mapping.COMPONENTS:
                    (cache / f'{suite}-{component}-Sources.gz').write_bytes(
                        gzip.compress(FIXTURE.read_bytes(), mtime=0))
            evidence = {'imageId': 'fixture', 'packages': [
                {'package': 'libsample1', 'version': '1:2.0-1+b2',
                 'sourceField': 'sample-source (1:2.0-1)', 'copyright': {'path': '/fixture'}}],
                'java': [], 'commonLicenses': [], 'assetSearch': {}, 'fileList': {}}
            ep = root / 'evidence.json'
            ep.write_bytes(mapping.encode(evidence))
            inventory = root / 'packages.tsv'
            inventory.write_text('libsample1:amd64\t1:2.0-1+b2\n', encoding='utf-8')
            with patch.object(mapping.urllib.request, 'urlopen', side_effect=AssertionError('通信は禁止')):
                first = mapping.generate(ep, inventory, cache, offline=True)
                second = mapping.generate(ep, inventory, cache, offline=True)
                self.assertEqual(mapping.encode(first), mapping.encode(second))
                self.assertEqual(1, first['summary']['matched'])
                self.assertEqual(16, len(first['indexes']))
                self.assertEqual(16, len(first['sources'][0]['indexes']))
                inventory.write_text('libsample1:amd64\tother\n', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, '一致'):
                    mapping.generate(ep, inventory, cache, offline=True)


if __name__ == '__main__':
    unittest.main()
