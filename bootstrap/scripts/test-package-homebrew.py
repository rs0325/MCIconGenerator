"""archiveの実inventoryと、symlink/空白path経由のCLI入出力契約を確認する。"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import stat
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('packaging', Path(__file__).with_name('package-homebrew.py'))
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class HomebrewPackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.image = self.root / 'MCIconBootstrap.app'
        self.config = self.image / 'Contents/app/bootstrap-package.json'
        self.config.parent.mkdir(parents=True)
        self.config.write_text(json.dumps({'owner': 'homebrew', 'bootstrapVersion': '1.1.0', 'host': {'os': 'macos', 'arch': 'aarch64'}}))
        self.entry = self.image / 'Contents/MacOS/MCIconBootstrap'
        self.entry.parent.mkdir(parents=True)
        self.entry.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\ncat\nprintf "fixture stderr\\n" >&2\nexit 17\n')
        self.entry.chmod(0o755)
        java = self.image / 'Contents/runtime/Contents/Home/bin/java'
        java.parent.mkdir(parents=True)
        java.write_text('fixture Java placeholder\n')
        java.chmod(0o755)
        self.bundle = self.root / 'candidate.tar.gz'

    def test_archive_inventory_and_symlink_launcher_contract(self):
        packaging.archive(self.image, self.bundle)
        evidence = json.loads(self.bundle.with_suffix('.json').read_text())
        self.assertEqual(hashlib.sha256(self.bundle.read_bytes()).hexdigest(), evidence['sha256'])
        prefix = self.root / 'Cellar with space'
        prefix.mkdir()
        with tarfile.open(self.bundle) as archive:
            self.assertTrue(all(member.isfile() or member.isdir() for member in archive))
            archive.extractall(prefix, filter='data')
        installed = next(prefix.iterdir())
        for record in evidence['files']:
            file = installed / record['path']
            self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(), record['sha256'])
            self.assertEqual(format(stat.S_IMODE(file.stat().st_mode), 'o'), record['mode'])
        external = self.root / 'prefix with space/bin'
        external.mkdir(parents=True)
        (external / 'mcicon').symlink_to(os.path.relpath(installed / 'bin/mcicon', external))
        chained = self.root / 'absolute link'
        chained.symlink_to(external / 'mcicon')
        args = ['argument with space', '', 'literal $HOME;*', '日本語']
        result = subprocess.run([str(chained), *args], input='fixture stdin\n', capture_output=True, text=True, timeout=10)
        self.assertEqual(17, result.returncode)
        self.assertEqual('\n'.join(args) + '\nfixture stdin\n', result.stdout)
        self.assertEqual('fixture stderr\n', result.stderr)

    def test_formula_requires_matching_hash_and_explicit_https(self):
        packaging.archive(self.image, self.bundle)
        digest = hashlib.sha256(self.bundle.read_bytes()).hexdigest()
        output = self.root / 'mcicon-bootstrap.rb'
        for url in ('http://example.invalid/app.tar.gz', 'https://user:secret@example.invalid/app.tar.gz', 'https://example.invalid/app#fragment'):
            with self.assertRaises(ValueError):
                packaging.formula(self.bundle, url, digest, output)
        with self.assertRaises(ValueError):
            packaging.formula(self.bundle, 'https://example.invalid/app.tar.gz', '0' * 64, output)
        url = "https://example.invalid/quote'and-literal.tar.gz"
        packaging.formula(self.bundle, url, digest, output)
        result = subprocess.run(['ruby', '-c', str(output)], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('depends_on arch: :arm64', output.read_text())
        checking = '''require "rubygems/version"
require "stringio"
Version = Gem::Version
class Formula
  def self.method_missing(*)
  end
  def self.test
  end
  def odie(message)
    raise message
  end
  def libexec
    raise "install-started"
  end
end
class IO
  def self.popen(*)
    yield StringIO.new(ENV.fetch("QA_MACOS_VERSION"))
  end
end
load ARGV.fetch(0)
begin
  MciconBootstrap.new.install
rescue => error
  puts error.message
end
'''
        for version, expected in [('15.7.6', 'macOS 15.7.7以降が必要です'), ('15.7.7', 'install-started'), ('15.8.1', 'install-started')]:
            result = subprocess.run(['ruby', '-e', checking, str(output)], env={**os.environ, 'QA_MACOS_VERSION': version}, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, result.stdout.strip())
        with self.assertRaises(FileExistsError):
            packaging.formula(self.bundle, url, digest, output)

    def test_foreign_owner_links_and_existing_output_are_preserved(self):
        package = json.loads(self.config.read_text())
        package['owner'] = 'archive'
        self.config.write_text(json.dumps(package))
        with self.assertRaises(ValueError):
            packaging.archive(self.image, self.bundle)
        self.assertFalse(self.bundle.exists())
        package['owner'] = 'homebrew'
        package['host']['arch'] = 'x86_64'
        self.config.write_text(json.dumps(package))
        with self.assertRaises(ValueError):
            packaging.archive(self.image, self.bundle)
        package['host']['arch'] = 'aarch64'
        self.config.write_text(json.dumps(package))
        (self.image / 'external').symlink_to(self.root / 'outside')
        with self.assertRaises(ValueError):
            packaging.archive(self.image, self.bundle)
        (self.image / 'external').unlink()
        self.bundle.write_bytes(b'preserved candidate')
        with self.assertRaises(ValueError):
            packaging.archive(self.image, self.bundle)
        self.assertEqual(b'preserved candidate', self.bundle.read_bytes())

    def test_audit_output_collision_and_creation_race_preserve_files(self):
        collision = self.root / 'candidate.json'
        with self.assertRaises(ValueError):
            packaging.archive(self.image, collision)
        self.assertFalse(collision.exists())
        audit = self.bundle.with_suffix('.json')
        audit.write_bytes(b'preserved audit')
        with self.assertRaises(ValueError):
            packaging.archive(self.image, self.bundle)
        self.assertEqual(b'preserved audit', audit.read_bytes())
        self.assertFalse(self.bundle.exists())
        audit.unlink()
        original_open = Path.open

        def open_with_audit(path, mode='r', *args, **kwargs):
            if path == self.bundle and mode == 'xb':
                audit.write_bytes(b'concurrent audit')
            return original_open(path, mode, *args, **kwargs)

        with mock.patch.object(Path, 'open', open_with_audit):
            with self.assertRaises(FileExistsError):
                packaging.archive(self.image, self.bundle)
        self.assertEqual(b'concurrent audit', audit.read_bytes())
        with tarfile.open(self.bundle) as archive:
            self.assertTrue(archive.getmembers())


if __name__ == '__main__':
    unittest.main()
