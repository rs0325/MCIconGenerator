"""実crossbuild PEの包装整合を調べる。Windows実行の代用にはしない。"""
import argparse, importlib.util, json, pathlib, shutil, tempfile, unittest
from security_artifact import verify, pe_imports

spec = importlib.util.spec_from_file_location('stage_reader', pathlib.Path(__file__).with_name('stage-windows-security-reader.py'))
stager = importlib.util.module_from_spec(spec); spec.loader.exec_module(stager)


class PackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.temporary.name)
        self.native = self.root / 'native'; shutil.copytree(NATIVE, self.native)
        self.inputs = self.root / 'input'; self.inputs.mkdir(); (self.inputs / 'fixture.jar').write_bytes(b'fixture-not-java')
        self.output = self.root / 'output'

    def tearDown(self): self.temporary.cleanup()

    def test_actual_pe_stage_and_exclusive_output(self):
        value = stager.stage(self.inputs, self.native, self.output, SOURCE)
        self.assertEqual(value['executable']['sha256'], json.loads((self.native / 'build.json').read_text())['artifact']['sha256'])
        self.assertEqual((self.output / 'support/windows-security-reader.exe').read_bytes(), (self.native / 'windows-security-reader.exe').read_bytes())
        self.assertGreater(len(value['licenses']), 4)
        with self.assertRaises(RuntimeError): stager.stage(self.inputs, self.native, self.output, SOURCE)

    def test_license_and_binary_tamper_rejected_before_output(self):
        license = self.native / 'licenses/LICENSE.TXT'; license.write_text('changed')
        with self.assertRaises(RuntimeError): stager.stage(self.inputs, self.native, self.output, SOURCE)
        self.assertFalse(self.output.exists())

    def test_unknown_import_and_wrong_source_rejected(self):
        file = self.native / 'windows-security-reader.exe'; data = file.read_bytes(); self.assertIn(b'ntdll.dll', data)
        file.write_bytes(data.replace(b'ntdll.dll', b'evilx.dll'))
        with self.assertRaises(RuntimeError): pe_imports(file)
        with self.assertRaises(RuntimeError): verify(self.native, SOURCE)

    def test_reserved_support_and_input_link_rejected(self):
        (self.inputs / 'support').mkdir(); (self.inputs / 'support/windows-security-reader-build.json').write_text('preserve')
        with self.assertRaises(RuntimeError): stager.stage(self.inputs, self.native, self.output, SOURCE)
        self.assertEqual((self.inputs / 'support/windows-security-reader-build.json').read_text(), 'preserve')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--native', type=pathlib.Path, required=True); args = parser.parse_args()
    NATIVE = args.native.resolve(); SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'src/main/native/windows-security-reader.cpp'
    unittest.main(argv=['test-security-reader-package'])
