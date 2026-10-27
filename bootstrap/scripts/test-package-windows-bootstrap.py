"""固定readerの実stageと、Windows生成command/bytes境界を確認する。実JVM/Windows受入ではない。"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from unittest import mock

spec = importlib.util.spec_from_file_location('windows_package', Path(__file__).with_name('package-windows-bootstrap.py'))
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class WindowsPackageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / 'path with space'; self.root.mkdir()
        self.jdk = self.root / 'jdk'; (self.jdk / 'bin').mkdir(parents=True)
        for name in ('java.exe', 'jlink.exe', 'jpackage.exe'): (self.jdk / 'bin' / name).write_bytes(b'fixture-tool')
        (self.jdk / 'release').write_text('JAVA_VERSION="21.0.12.1"\nOS_ARCH="x86_64"\nOS_NAME="Windows"\n', encoding='utf-8')
        self.inputs = self.root / 'lib'; self.inputs.mkdir()
        (self.inputs / 'bootstrap-1.1.0.jar').write_bytes(b'fixture-bootstrap')
        (self.inputs / 'dependency.jar').write_bytes(b'fixture-dependency')
        self.seed = self.root / 'seed'; self.seed.mkdir()
        for name in ('stable.json', 'stable.json.sig', 'tool.zip'): (self.seed / name).write_bytes(b'fixture-input-not-signed')
        (self.seed / 'not-distributed.txt').write_bytes(b'not-part-of-seed')
        self.trust = self.root / 'trust.der'; self.trust.write_bytes(b'fixture-public-trust')
        self.core = self.root / 'core.jar'; self.core.write_bytes(b'fixture-current-core')
        self.resources = self.root / 'resources'; self.resources.mkdir(); (self.resources / 'pins.json').write_bytes(b'fixture-resources')
        self.native = self.root / 'native'; shutil.copytree(NATIVE, self.native)
        self.output = self.root / 'candidate'; self.calls = []; self.failure = None
        for patch in [mock.patch.object(packaging.sys, 'platform', 'win32'), mock.patch.object(packaging.platform, 'machine', return_value='AMD64'), mock.patch.object(packaging.subprocess, 'run', side_effect=self.tool)]:
            patch.start(); self.addCleanup(patch.stop)

    def tool(self, args, **kwargs):
        self.calls.append(args)
        self.assertEqual(kwargs, {'stdin': subprocess.DEVNULL, 'check': True, 'timeout': 120})
        kind = Path(args[0]).name
        if kind == 'java.exe':
            if self.failure == 'verifier': raise subprocess.CalledProcessError(1, args)
            if self.failure == 'extra-jar': (self.inputs / 'late.jar').write_bytes(b'not-verified')
            if self.failure == 'timeout': raise subprocess.TimeoutExpired(args, 120)
            self.assertIn('io.github.rs0325.mcicon.bootstrap.ManagedPackageVerifier', args)
            self.assertEqual(args[-5:], [str(self.resources), str(self.seed), str(self.trust), '1.1.0', str(self.core)])
        elif kind == 'jlink.exe':
            self.assertEqual(args[args.index('--add-modules') + 1], 'java.se,jdk.crypto.ec,jdk.security.auth')
            runtime = Path(args[args.index('--output') + 1]); (runtime / 'bin').mkdir(parents=True)
            (runtime / 'bin/java.exe').write_bytes(b'fixture-runtime-not-PE')
        elif kind == 'jpackage.exe':
            image = Path(args[args.index('--dest') + 1]) / 'mcicon'; image.mkdir(parents=True)
            shutil.copytree(Path(args[args.index('--input') + 1]), image / 'app')
            shutil.copytree(Path(args[args.index('--runtime-image') + 1]), image / 'runtime')
            (image / 'mcicon.exe').write_bytes(b'fixture-launcher-not-PE')
            (image / 'app/mcicon.cfg').write_bytes(b'fixture-config')
            if self.failure == 'image-tamper': (image / 'app/bootstrap-1.1.0.jar').write_bytes(b'changed')
            if self.failure == 'missing-java': (image / 'runtime/bin/java.exe').unlink()
            if self.failure == 'input-tamper': self.core.write_bytes(b'changed')
        else: self.fail('予定外command')
        return subprocess.CompletedProcess(args, 0)

    def generate(self, **values):
        args = dict(jdk=self.jdk, inputs=self.inputs, seed=self.seed, trust=self.trust, resources=self.resources,
                    core=self.core, native=self.native, version='1.1.0', owner='winget', host_os='windows', arch='x86_64', output=self.output)
        return packaging.package(**(args | values))

    def test_generation_preserves_space_argv_and_all_tree_bytes(self):
        audit = self.generate()
        self.assertFalse(audit['privateKeyGenerated'])
        self.assertEqual([Path(args[0]).name for args in self.calls], ['java.exe', 'jlink.exe', 'jpackage.exe'])
        self.assertEqual(self.calls[0][self.calls[0].index('-cp') + 1], ';'.join(str(p) for p in sorted(self.inputs.glob('*.jar'))))
        self.assertEqual(self.calls[2][self.calls[2].index('--input') + 1], str(self.output / 'staging/input'))
        self.assertEqual((self.output / 'image/mcicon/app/support/windows-security-reader.exe').read_bytes(), (self.native / 'windows-security-reader.exe').read_bytes())
        self.assertFalse((self.output / 'image/mcicon/app/seed/not-distributed.txt').exists())
        with zipfile.ZipFile(self.output / 'candidate.zip') as archive:
            for row in audit['files']:
                data = archive.read(row['path']); self.assertEqual(len(data), row['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), row['sha256'])
        self.assertEqual(json.loads((self.output / 'packaging.json').read_text(encoding='utf-8')), audit)

    def test_existing_output_is_preserved_without_tool_start(self):
        self.output.mkdir(); (self.output / 'keep').write_bytes(b'keep')
        with self.assertRaises(RuntimeError): self.generate()
        self.assertEqual((self.output / 'keep').read_bytes(), b'keep'); self.assertEqual(self.calls, [])

    def test_verifier_failure_or_timeout_stops_before_runtime(self):
        for failure in ('verifier', 'timeout'):
            with self.subTest(failure=failure):
                self.failure = failure; self.output = self.root / failure; self.calls = []
                with self.assertRaises(subprocess.SubprocessError): self.generate()
                self.assertEqual(len(self.calls), 1); self.assertFalse((self.output / 'packaging.json').exists())

    def test_license_missing_or_tamper_is_rejected_by_existing_stager(self):
        path = self.native / 'licenses/LICENSE.TXT'; original = path.read_bytes()
        for failure in ('missing', 'changed'):
            with self.subTest(failure=failure):
                self.output = self.root / failure; self.calls = []
                if failure == 'missing': path.unlink()
                else: path.write_bytes(b'changed')
                with self.assertRaises(RuntimeError): self.generate()
                self.assertEqual(len(self.calls), 0 if failure == 'missing' else 1); self.assertFalse((self.output / 'staging/runtime').exists())
                path.write_bytes(original)

    def test_image_missing_or_changed_input_cannot_become_success(self):
        for failure in ('image-tamper', 'missing-java', 'input-tamper'):
            with self.subTest(failure=failure):
                self.failure = failure; self.output = self.root / failure
                with self.assertRaises(RuntimeError): self.generate()
                self.assertFalse((self.output / 'packaging.json').exists())

    def test_verifier_added_jar_and_extra_staged_file_are_rejected(self):
        self.failure = 'extra-jar'
        with self.assertRaises(RuntimeError): self.generate()
        self.assertEqual(len(self.calls), 1); self.assertFalse((self.output / 'packaging.json').exists())
        (self.inputs / 'late.jar').unlink(); self.failure = None; self.output = self.root / 'extra-stage'; self.calls = []
        stage = packaging.stager.stage
        def changed_stage(*args):
            value = stage(*args); (args[2] / 'extra.jar').write_bytes(b'not-verified'); return value
        with mock.patch.object(packaging.stager, 'stage', side_effect=changed_stage), self.assertRaises(RuntimeError): self.generate()
        self.assertEqual(len(self.calls), 1); self.assertFalse((self.output / 'packaging.json').exists())

    def test_native_copy_changes_are_rejected(self):
        copy = shutil.copyfile
        for name in ('windows-security-reader.exe', 'build.json', 'licenses/LICENSE.TXT'):
            with self.subTest(name=name):
                shutil.rmtree(self.native); shutil.copytree(NATIVE, self.native); self.output = self.root / Path(name).name; self.calls = []
                target = self.native / name; changed = False
                def changed_copy(source, destination, **kwargs):
                    nonlocal changed
                    if Path(source) == target and not changed:
                        changed = True; target.write_bytes(target.read_bytes() + b'changed-after-verify')
                    return copy(source, destination, **kwargs)
                with mock.patch.object(packaging.stager.shutil, 'copyfile', side_effect=changed_copy), self.assertRaises(RuntimeError): self.generate()
                self.assertTrue(changed); self.assertEqual(len(self.calls), 1); self.assertFalse((self.output / 'packaging.json').exists())

    def test_wrong_host_owner_version_and_jdk_rejected_before_output(self):
        for values in [dict(host_os='linux'), dict(arch='aarch64'), dict(owner='homebrew'), dict(version='2.0.0')]:
            with self.subTest(values=values), self.assertRaises(RuntimeError): self.generate(**values)
        (self.jdk / 'release').write_text('JAVA_VERSION="17.0.1"\nOS_ARCH="x86_64"\nOS_NAME="Windows"\n', encoding='utf-8')
        with self.assertRaises(RuntimeError): self.generate()
        self.assertFalse(self.output.exists()); self.assertEqual(self.calls, [])

    def test_tree_rejects_link_without_entering_its_target(self):
        target = self.root / 'target'; target.mkdir(); (target / 'keep').write_bytes(b'keep')
        link = self.inputs / 'linked'; link.symlink_to(target, target_is_directory=True)
        with self.assertRaises(RuntimeError): self.generate()
        self.assertFalse(self.output.exists()); self.assertEqual(self.calls, [])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--native', type=Path, required=True); args = parser.parse_args()
    NATIVE = args.native.resolve()
    unittest.main(argv=['test-package-windows-bootstrap'])
