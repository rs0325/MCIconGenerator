"""合成imageと実WiX/MSI read-only APIで包装を検証する。install・起動は行わない。"""
from pathlib import Path
import argparse
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('package-windows-installer.py'))
installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)


def fixture(root):
    candidate = root / 'input with space'; image = candidate / 'image/mcicon'
    files = ['mcicon.exe', 'app/mcicon.cfg', 'runtime/bin/java.exe', 'runtime/legal/java.base/LICENSE',
             'app/bootstrap-1.1.0.jar', 'app/support/windows-security-reader.exe', 'app/trust.der',
             'app/seed/stable.json', 'app/seed/stable.json.sig', 'app/seed/tool.zip']
    files += ['app/support/security-reader/licenses/' + name for name in installer.LICENSES]
    for name in files:
        path = image / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'fixture-not-runtime-or-signed-seed')
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        shutil.copyfile(installer.REPO / name, image / 'app' / name)
        target = image / 'app/support/security-reader/licenses/mcicon' / name
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(installer.REPO / name, target)
    shutil.copytree(installer.REPO / 'licenses', image / 'app/licenses')
    support = image / 'app/support'
    installer.write_json(support / 'windows-security-reader.json', {'licenses': [
        {'path': path.relative_to(support).as_posix(), 'bytes': path.stat().st_size, 'sha256': installer.digest(path)}
        for path in installer.image_packager.tree(support / 'security-reader') if path.is_file()]})
    package = {'schemaVersion': 1, 'bootstrapVersion': '1.1.0', 'bootstrapProtocol': 3, 'owner': 'winget',
               'host': {'os': 'windows', 'arch': 'x86_64'}, 'stableManifestUrl': None}
    installer.write_json(image / 'app/bootstrap-package.json', package)
    refresh(candidate, package)
    return candidate


def refresh(candidate, package):
    image = candidate / 'image/mcicon'; archive = candidate / 'candidate.zip'
    with zipfile.ZipFile(archive, 'w') as bundle:
        for row in installer.image_packager.inventory(image): bundle.write(image / row['path'], row['path'])
    record = {'status': 'windows-app-image-and-archive-candidate-not-installer-accepted', 'package': package,
              'files': installer.image_packager.inventory(image), 'archive': archive.name,
              'bytes': archive.stat().st_size, 'sha256': installer.digest(archive)}
    (candidate / 'packaging.json').write_text(json.dumps(record), encoding='utf-8')


class InstallerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / 'path with space'; self.root.mkdir()
        self.candidate = fixture(self.root); self.output = self.root / 'installer with space'

    def build(self): return installer.build(self.candidate, WIX, '1.1.0', self.output)

    def test_real_msi_build_and_readonly_audit(self):
        record = self.build()
        self.assertEqual(installer.audit(self.output, WIX, self.root / 'audit with space'), record)
        self.assertEqual(record['payloadUpdateOwner'], 'bootstrapper')
        self.assertEqual(record['bootstrapUpdateOwner'], 'winget')
        self.assertEqual(record['installRoot'], '%LOCALAPPDATA%\\Programs\\MCIconGenerator')
        self.assertFalse(record['tables']['CustomAction'])
        self.assertTrue(all(row[2] == '' and row[4] == '2' for row in record['tables']['RemoveFile']))
        self.assertEqual(json.loads((self.output / 'winget.yaml').read_text(encoding='utf-8'))['InstallerType'], 'msi')

    def test_reject_modified_manifest_checksum_source_and_payload(self):
        self.build()
        mutations = {'winget.yaml': b'{}', 'SHA256SUMS': b'0  candidate.msi\n', 'candidate.msi': b'changed',
                     'installer.wxs': b'changed', 'payload/app/LICENSE': b'changed'}
        for name, data in mutations.items():
            with self.subTest(name=name):
                path = self.output / name; original = path.read_bytes(); path.write_bytes(data)
                with self.assertRaises(RuntimeError): installer.audit(self.output, WIX, self.root / 'not-created')
                self.assertFalse((self.root / 'not-created').exists()); path.write_bytes(original)

    def test_existing_output_and_invalid_version_do_not_start_tools(self):
        with mock.patch.object(installer, 'run') as run:
            self.output.mkdir(); (self.output / 'keep').write_bytes(b'keep')
            with self.assertRaises(RuntimeError): self.build()
            self.assertEqual((self.output / 'keep').read_bytes(), b'keep')
            for version in ('1.1.0-SNAPSHOT', '256.1.0', '1.1.65536', '01.1.0', '1.1.0\n', '../bad'):
                with self.subTest(version=version), self.assertRaises(RuntimeError):
                    installer.build(self.candidate, WIX, version, self.root / 'new')
            run.assert_not_called()

    def test_input_hash_and_license_missing_or_changed_are_rejected(self):
        package = json.loads((self.candidate / 'packaging.json').read_text(encoding='utf-8'))['package']
        for name in ('app/LICENSE', 'app/THIRD_PARTY_NOTICES.md', 'app/support/security-reader/licenses/LICENSE.TXT',
                     'runtime/legal/java.base/LICENSE', 'mcicon.exe'):
            with self.subTest(name=name):
                path = self.candidate / 'image/mcicon' / name; original = path.read_bytes(); path.unlink()
                refresh(self.candidate, package)
                with self.assertRaises(RuntimeError): self.build()
                self.assertFalse(self.output.exists()); path.write_bytes(original)
        path = self.candidate / 'image/mcicon/app/LICENSE'; path.write_bytes(b'changed')
        refresh(self.candidate, package)
        with self.assertRaises(RuntimeError): self.build()
        self.assertFalse(self.output.exists())

    def test_input_bytes_and_wrong_owner_rejected(self):
        path = self.candidate / 'image/mcicon/mcicon.exe'; path.write_bytes(b'changed-after-record')
        with self.assertRaises(RuntimeError): self.build()
        info = json.loads((self.candidate / 'packaging.json').read_text(encoding='utf-8'))['package']
        info['owner'] = 'archive'; refresh(self.candidate, info)
        with self.assertRaises(RuntimeError): self.build()
        self.assertFalse(self.output.exists())

    def test_denied_output_and_tool_failure_leave_no_completion_record(self):
        original = Path.mkdir
        def denied(path, *args, **kwargs):
            if path == self.output: raise PermissionError('fixture access denied')
            return original(path, *args, **kwargs)
        with mock.patch.object(Path, 'mkdir', denied), mock.patch.object(installer, 'run') as run:
            with self.assertRaises(PermissionError): self.build()
            run.assert_not_called()
        for error in (PermissionError('fixture denied'), subprocess.CalledProcessError(5, ['fixture']), subprocess.TimeoutExpired(['fixture'], 120)):
            with self.subTest(error=type(error).__name__):
                self.output = self.root / type(error).__name__
                with mock.patch.object(installer, 'run', side_effect=error), self.assertRaises(type(error)): self.build()
                self.assertFalse((self.output / 'installer-audit.json').exists())

    def test_static_tables_reject_machine_path_custom_action_and_file_deletion(self):
        record = self.build()
        for table, row in [('CustomAction', ['RunSomething']), ('Environment', ['MachinePath', '=*-PATH', '[INSTALLFOLDER]', 'X']),
                           ('RemoveFile', ['DeleteData', 'C', '*', 'INSTALLFOLDER', '2']),
                           ('Directory', ['INSTALLFOLDER', 'TARGETDIR', 'cache']),
                           ('LaunchCondition', ['VersionNT64', 'machine also accepted'])]:
            with self.subTest(table=table):
                tables = {**record['tables'], table: [row]}
                with self.assertRaises(RuntimeError): installer.check_tables(tables, record['files'], '1.1.0', record['productCode'])

    def test_reader_license_tamper_with_updated_front_inventory_is_rejected(self):
        path = self.candidate / 'image/mcicon/app/support/security-reader/licenses/LICENSE.TXT'
        path.write_bytes(b'changed')
        info = json.loads((self.candidate / 'packaging.json').read_text(encoding='utf-8'))['package']
        refresh(self.candidate, info)
        with self.assertRaises(RuntimeError): self.build()
        self.assertFalse(self.output.exists())

    def test_duplicate_zip_entry_and_archive_hash_mismatch_are_rejected(self):
        archive = self.candidate / 'candidate.zip'
        with archive.open('ab') as stream: stream.write(b'changed')
        with self.assertRaises(RuntimeError): self.build()
        info = json.loads((self.candidate / 'packaging.json').read_text(encoding='utf-8'))['package']
        refresh(self.candidate, info)
        with zipfile.ZipFile(archive, 'a') as bundle:
            bundle.writestr('mcicon.exe', b'duplicate')
        record_path = self.candidate / 'packaging.json'; record = json.loads(record_path.read_text(encoding='utf-8'))
        record.update(bytes=archive.stat().st_size, sha256=installer.digest(archive)); record_path.write_text(json.dumps(record), encoding='utf-8')
        with self.assertRaises(RuntimeError): self.build()

    def test_tool_argv_keeps_space_paths_and_propagates_failure(self):
        with mock.patch.object(installer.subprocess, 'run', return_value=subprocess.CompletedProcess([], 5, b'fixture denied')) as run:
            with self.assertRaises(subprocess.CalledProcessError): installer.run([WIX / 'candle.exe', self.output])
            self.assertEqual(run.call_args.args[0], [str(WIX / 'candle.exe'), str(self.output)])
            self.assertEqual(run.call_args.kwargs['timeout'], 120)

    def test_extracted_cabinet_bytes_are_checked(self):
        record = self.build(); path = next((self.output / 'extracted/File').iterdir()); path.write_bytes(b'tamper')
        with self.assertRaises(RuntimeError): installer.check_payload(self.output / 'extracted', record['files'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--wix', type=Path, required=True)
    parser.add_argument('--fixture-output', type=Path)
    args = parser.parse_args(); WIX = args.wix.resolve()
    if args.fixture_output:
        root = args.fixture_output.absolute(); root.mkdir(parents=True)
        candidate = fixture(root); installer.build(candidate, WIX, '1.1.0', root / 'installer with space')
        installer.audit(root / 'installer with space', WIX, root / 'audit with space')
    else: unittest.main(argv=['test-package-windows-installer'])
