"""PE/manifestの実検査と、OS包装コマンドを隔離した包装入口を検査する。"""
import importlib.util, json, pathlib, struct, tempfile, unittest
from unittest.mock import patch
from native_artifact import BUILD_FLAGS, LINK_FLAGS, LICENSE_PATHS, TOOLCHAIN_SHA256, digest, pe_imports, verify

spec = importlib.util.spec_from_file_location('packager', pathlib.Path(__file__).with_name('package-helper.py'))
packager = importlib.util.module_from_spec(spec); spec.loader.exec_module(packager)

def image(dll='KERNEL32.dll'):
    data = bytearray(1024); data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 128); data[128:132] = b'PE\0\0'
    struct.pack_into('<HH', data, 132, 0x8664, 1); struct.pack_into('<HH', data, 148, 240, 2)
    struct.pack_into('<H', data, 152, 0x20b); struct.pack_into('<H', data, 220, 3); struct.pack_into('<I', data, 260, 16)
    struct.pack_into('<II', data, 272, 0x1000, 40); struct.pack_into('<IIII', data, 400, 512, 0x1000, 512, 512)
    struct.pack_into('<IIIII', data, 512, 0, 0, 0, 0x1040, 0); data[576:576 + len(dll) + 1] = dll.encode() + b'\0'
    return data

class NativeArtifactTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.tmp.name)
        self.native = self.root / 'windows-host.exe'; self.native.write_bytes(image())
        self.source = self.root / 'windows-host.cpp'; self.source.write_text('固定source')
        self.build = self.root / 'build.json'; notices = []
        for relative in LICENSE_PATHS:
            path = self.root / 'licenses' / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('fixture notice')
            notices.append({'path': path.relative_to(self.root).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest(path)})
        self.manifest = {'schemaVersion': 1, 'target': 'x86_64-w64-mingw32', 'toolchainArchiveSha256': TOOLCHAIN_SHA256, 'sourceSha256': digest(self.source), 'flags': BUILD_FLAGS + LINK_FLAGS, 'artifact': {'bytes': self.native.stat().st_size, 'sha256': digest(self.native), 'imports': ['kernel32.dll']}, 'notices': notices, 'acceptance': 'crossbuild-only-not-native-os-accepted'}
        self.save()
    def tearDown(self): self.tmp.cleanup()
    def save(self): self.build.write_text(json.dumps(self.manifest))
    def test_pe_rejects_wrong_arch_runtime_and_truncation(self):
        self.assertEqual(pe_imports(self.native), ['kernel32.dll'])
        for data in [image('libc++.dll'), image()[:600], image()[:130]]:
            self.native.write_bytes(data)
            with self.assertRaises(RuntimeError): pe_imports(self.native)
        data = image(); struct.pack_into('<II', data, 368, 0x1000, 20); self.native.write_bytes(data)
        with self.assertRaisesRegex(RuntimeError, 'delay import'): pe_imports(self.native)
        data = image(); struct.pack_into('<H', data, 132, 0xaa64); self.native.write_bytes(data)
        with self.assertRaises(RuntimeError): pe_imports(self.native)
    def test_source_artifact_and_license_mismatch_rejected(self):
        verify(self.native, self.source, self.build)
        self.source.write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'source pin'): verify(self.native, self.source, self.build)
        self.source.write_text('固定source'); self.native.write_bytes(image('ole32.dll'))
        with self.assertRaisesRegex(RuntimeError, 'artifact pin'): verify(self.native, self.source, self.build)
        self.native.write_bytes(image()); (self.root / self.manifest['notices'][0]['path']).write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'notice pin'): verify(self.native, self.source, self.build)
    def test_packaging_binds_native_source_manifest_notices_and_final_bytes(self):
        payload = self.root / 'payload'; payload.mkdir(); (payload / 'guestbridge.py').write_text('fixture')
        bridge = payload / 'guestbridge.py'; (payload / 'payload.json').write_text(json.dumps({'schemaVersion': 1, 'guestBridgeAbi': 1, 'files': [{'path': 'guestbridge.py', 'bytes': bridge.stat().st_size, 'sha256': digest(bridge)}], 'guestBase': {'bridgeSha256': digest(bridge)}}))
        jars = self.root / 'jars'; jars.mkdir(); (jars / 'helper-windows-1.jar').write_text('jar')
        output = self.root / 'output'; calls = []
        def fake_run(args):
            calls.append([str(value) for value in args])
            if 'jpackage.exe' in str(args[0]):
                import shutil
                shutil.copytree(output / 'staging/input', output / 'image/MCIconHelper/app')
        argv = ['package-helper.py', '--jdk', str(self.root), '--input', str(jars), '--payload', str(payload), '--native', str(self.native), '--native-source', str(self.source), '--native-build', str(self.build), '--output', str(output)]
        with patch('sys.argv', argv), patch.object(packager.sys, 'platform', 'win32'), patch.object(packager, 'run', fake_run): packager.main()
        self.assertEqual(len(calls), 2); self.assertTrue(calls[0][0].endswith('jlink.exe')); self.assertTrue(calls[1][0].endswith('jpackage.exe'))
        result = json.loads((output / 'packaging.json').read_text()); self.assertEqual(result['nativeBuild']['manifestSha256'], digest(self.build)); self.assertEqual(result['nativeBuild']['sourceSha256'], digest(self.source))
        support = output / 'image/MCIconHelper/app/support'; self.assertEqual(digest(support / 'windows-host.exe'), digest(self.native))
        verify(support / 'windows-host.exe', self.source, support / 'native-build/build.json')
        manifest = json.loads((support / 'payload.json').read_text())
        inventory = {row['path']: row for row in result['files']}
        for relative in packager.PROJECT_NOTICES:
            name = 'licenses/mcicon/' + relative; target = support / name; source = packager.PROJECT_ROOT / relative
            self.assertEqual(target.read_bytes(), source.read_bytes())
            self.assertEqual(inventory['app/support/' + name]['sha256'], digest(source))
            self.assertIn({'path': name, 'bytes': source.stat().st_size, 'sha256': digest(source)}, manifest['files'])
        self.assertEqual((support / 'guestbridge.py').read_bytes(), bridge.read_bytes())
        self.assertEqual((output / 'image/MCIconHelper/app/helper-windows-1.jar').read_bytes(), b'jar')
    def test_project_notice_missing_rejects_before_output_or_jdk(self):
        payload = self.root / 'payload'; payload.mkdir(); bridge = payload / 'guestbridge.py'; bridge.write_bytes(b'fixture')
        (payload / 'payload.json').write_text(json.dumps({'schemaVersion': 1, 'guestBridgeAbi': 1, 'files': [{'path': 'guestbridge.py', 'bytes': 7, 'sha256': digest(bridge)}], 'guestBase': {'bridgeSha256': digest(bridge)}}))
        for missing in packager.PROJECT_NOTICES:
            with self.subTest(missing=missing):
                docs = self.root / 'docs'; docs.mkdir()
                for relative in packager.PROJECT_NOTICES:
                    if relative != missing:
                        path = docs / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'notice')
                output = self.root / 'not-created'
                argv = ['package-helper.py', '--jdk', str(self.root), '--input', str(self.root), '--payload', str(payload), '--native', str(self.native), '--native-source', str(self.source), '--native-build', str(self.build), '--output', str(output)]
                with patch('sys.argv', argv), patch.object(packager.sys, 'platform', 'win32'), patch.object(packager, 'PROJECT_ROOT', docs), patch.object(packager, 'run') as run:
                    with self.assertRaisesRegex(RuntimeError, 'project notice不足'): packager.main()
                run.assert_not_called(); self.assertFalse(output.exists())
                import shutil
                shutil.rmtree(docs)

if __name__ == '__main__': unittest.main()
