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

    def test_configured_dsc_is_offline_and_keeps_all_members(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            fields = self.fields[0]
            checksums = '\n '.join(line for line in fields['Checksums-Sha256'].splitlines()
                                   if line.strip() and not line.endswith('.dsc'))
            body = ('Source: sample-source\nVersion: 1:2.0-1\n'
                    'Binary: libsample1, sample-tools\nFormat: 3.0 (quilt)\n'
                    'Checksums-Sha256: ' + checksums + '\n')
            url = 'https://launchpad.net/example/sample-source_2.0-1.dsc'
            (cache / (mapping.digest(url.encode()) + '.metadata')).write_bytes(body.encode())
            package = {'package': 'libsample1', 'version': '1:2.0-1',
                       'sourceField': 'sample-source', 'sourceId': None,
                       'sourcePackage': 'sample-source', 'sourceVersion': '1:2.0-1'}
            table = {'packages': [package], 'javaReleases': [], 'dscChecks': []}
            with patch.object(metadata.urllib.request, 'urlopen', side_effect=AssertionError('通信は禁止')):
                result = metadata.collect_locations(table, cache,
                    {'sample-source@1:2.0-1': [url]}, offline=True)
                source = result['sources'][0]
                self.assertEqual(url, source['dsc']['url'])
                self.assertEqual(mapping.digest(body.encode()), source['dsc']['sha256'])
                self.assertEqual(2, len(source['sourceArchives']))
                self.assertEqual([], source['indexes'])
                self.assertEqual([], result['unresolved'])
                self.assertTrue(all('/example/' in f['url'] and '/example//' not in f['url']
                                    for f in source['files']))
                with self.assertRaises(FileNotFoundError):
                    metadata.fetch(url + '.absent', cache, offline=True)
                (cache / (mapping.digest(url.encode()) + '.metadata')).write_text(
                    body.replace('Version: 1:2.0-1', 'Version: 1:2.0-2'), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'source名/版'):
                    metadata.collect_locations(table, cache,
                        {'sample-source@1:2.0-1': [url]}, offline=True)

    def test_configured_search_preserves_failures_and_stops_at_success(self):
        table = {'packages': [{'sourceId': None, 'sourcePackage': 'native',
                               'sourceVersion': '3.0'}], 'javaReleases': [], 'dscChecks': []}
        body = ('Source: native\nVersion: 3.0\nBinary: native\n'
                'Checksums-Sha256: ' + 'a' * 64 + ' 12 native_3.0.tar.xz\n').encode()
        first, second = 'https://example.invalid/a.dsc', 'https://example.invalid/b.dsc'
        error = metadata.urllib.error.HTTPError(first, 404, 'fixture', {}, None)
        with patch.object(metadata, 'fetch', side_effect=[error,
                          (body, {'url': second, 'sha256': mapping.digest(body), 'bytes': len(body)})]) as fetch:
            result = metadata.collect_locations(table, Path('.'),
                                                {'native@3.0': [first, second, first]})
            self.assertEqual(2, fetch.call_count)
        self.assertEqual([404, 200], [r['httpStatus'] for r in result['sources'][0]['retrievalAttempts']])
        with patch.object(metadata, 'fetch', side_effect=error):
            result = metadata.collect_locations(table, Path('.'), {'native@3.0': [first]})
        self.assertEqual([], result['sources'])
        self.assertEqual(first, result['unresolved'][0]['attempts'][0]['url'])

    def test_recorded_supplement_is_reproducible_and_preserves_baseline(self):
        root = ROOT.parent
        baseline = root / 'qa/renderer-sources-2026-11-04.json'
        supplemental = root / 'qa/renderer-sources-2026-11-04-b2b-metadata.json'
        recorded = root / 'qa/renderer-sources-2026-11-04-b2b.json'
        before = baseline.read_bytes()
        with patch.object(mapping.urllib.request, 'urlopen', side_effect=AssertionError('通信は禁止')):
            result = mapping.supplement(baseline, supplemental)
        self.assertEqual(recorded.read_bytes(), mapping.encode(result))
        self.assertEqual(before, baseline.read_bytes())
        self.assertEqual(220, result['summary']['matched'])
        self.assertEqual(0, result['summary']['unmatched'])
        self.assertEqual(10, result['summary']['matchedFromDsc'])
        self.assertEqual(mapping.digest(before.replace(b'\r\n', b'\n')), result['baselineTableSha256'])
        old = json.loads(before)
        for key in ('indexes', 'evidenceSha256', 'binaryInventorySha256', 'imageId'):
            self.assertEqual(old[key], result[key])
        sources = {s['id']: s for s in result['sources']}
        for source in old['sources']:
            self.assertEqual(source, sources[source['id']])

    def test_supplement_rejects_duplicate_and_wrong_version(self):
        root = ROOT.parent
        baseline = root / 'qa/renderer-sources-2026-11-04.json'
        original = json.loads((root / 'qa/renderer-sources-2026-11-04-b2b-metadata.json').read_bytes())
        with tempfile.TemporaryDirectory() as temp:
            crlf = Path(temp) / 'baseline.json'
            crlf.write_bytes(baseline.read_bytes().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n'))
            self.assertEqual(mapping.encode(mapping.supplement(baseline,
                root / 'qa/renderer-sources-2026-11-04-b2b-metadata.json')),
                mapping.encode(mapping.supplement(crlf,
                root / 'qa/renderer-sources-2026-11-04-b2b-metadata.json')))
            mp = Path(temp) / 'metadata.json'
            for sources in ([original['sources'][0]] * 2,
                            [{**original['sources'][0], 'version': 'wrong'}]):
                mp.write_bytes(mapping.encode({**original, 'sources': sources}))
                with self.assertRaisesRegex(ValueError, '補足source'):
                    mapping.supplement(baseline, mp)
            source = {**original['sources'][0], 'binaryNames': []}
            mp.write_bytes(mapping.encode({**original, 'sources': [source]}))
            result = mapping.supplement(baseline, mp)
            packages = [p for p in result['packages'] if p['sourcePackage'] == source['package']]
            self.assertTrue(all(p['sourceId'] is None and 'Binary' in p['unmatchedReason'] for p in packages))


if __name__ == '__main__':
    unittest.main()
