"""Mac生成器のmetadata契約を代役toolで確認する。実Mac生成・署名検証ではない。"""
from pathlib import Path
import json
import runpy
import shutil
import sys
import tempfile
import unittest
from unittest import mock


class BootstrapPackageTest(unittest.TestCase):
    def test_generation_uses_protocol_three_in_image_and_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = root / 'lib'; inputs.mkdir()
            (inputs / 'bootstrap-1.1.0.jar').write_bytes(b'fixture-bootstrap')
            seed = root / 'seed'; seed.mkdir()
            for name in ('stable.json', 'stable.json.sig', 'tool.zip'):
                (seed / name).write_bytes(b'fixture-not-signed')
            trust = root / 'trust.der'; trust.write_bytes(b'fixture-trust')
            output = root / 'candidate'
            script = Path(__file__).with_name('package-bootstrap.py')
            args = [str(script), '--jdk', str(root / 'jdk'), '--input', str(inputs),
                    '--seed', str(seed), '--trust', str(trust), '--version', '1.1.0',
                    '--owner', 'homebrew', '--output', str(output)]
            calls = []

            def tool(command, **kwargs):
                calls.append(Path(command[0]).name)
                if calls[-1] == 'jlink':
                    Path(command[command.index('--output') + 1]).mkdir()
                elif calls[-1] == 'jpackage':
                    app = output / 'image/MCIconBootstrap.app/Contents/app'
                    shutil.copytree(Path(command[command.index('--input') + 1]), app)
                else:
                    self.assertEqual(calls[-1], 'codesign')

            with mock.patch.object(sys, 'argv', args), mock.patch.object(sys, 'platform', 'darwin'), \
                    mock.patch('platform.machine', return_value='arm64'), mock.patch('subprocess.run', side_effect=tool):
                runpy.run_path(str(script), run_name='__main__')
            metadata = json.loads((output / 'image/MCIconBootstrap.app/Contents/app/bootstrap-package.json').read_text())
            self.assertEqual(metadata['schemaVersion'], 1)
            self.assertEqual(metadata['bootstrapProtocol'], 3)
            self.assertEqual(metadata['host'], {'os': 'macos', 'arch': 'aarch64'})
            self.assertEqual(json.loads((output / 'packaging.json').read_text())['package'], metadata)
            self.assertEqual(calls, ['jlink', 'jpackage', 'codesign'])


if __name__ == '__main__':
    unittest.main()
