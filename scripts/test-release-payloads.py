"""署名後payload工具の採用gate・core結合と包装を検証する。"""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('payloads', Path(__file__).with_name('prepare-release-payloads.py'))
payloads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payloads)


class PayloadTest(unittest.TestCase):
    def test_rejects_unadopted_input_without_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'input.json').write_text('{"finalBytesConfirmed":false}')
            args = SimpleNamespace(runtime_input=root, output=root / 'out')
            with self.assertRaisesRegex(ValueError, '採用済み'):
                payloads.prepare(args)
            self.assertFalse(args.output.exists())

    def test_rejects_mismatched_core_resource(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            resource = root / 'resources/META-INF/mcicon/runtime/pins.json'
            resource.parent.mkdir(parents=True)
            resource.write_bytes(b'{}')
            core = root / 'core.jar'
            with zipfile.ZipFile(core, 'w') as jar:
                jar.writestr('META-INF/mcicon/runtime/pins.json', b'changed')
            with self.assertRaisesRegex(ValueError, '一致しません'):
                payloads.core_resources(core, root / 'resources')

    def test_two_host_payload_layout_and_hashes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            resources = root / 'resources'
            prefix = resources / 'META-INF/mcicon/runtime'
            prefix.mkdir(parents=True)
            cli = root / 'cli'
            cli.mkdir()
            runtime = root / 'runtime'
            (runtime / 'descriptors').mkdir(parents=True)
            (runtime / 'artifacts').mkdir()
            worker = root / 'agent.jar'
            worker.write_bytes(b'synthetic-agent')
            trust = b'synthetic-public-trust'
            (prefix / 'trust-set.bin').write_bytes(trust)
            entries = []
            for host, (os_name, arch, _, _) in payloads.candidate.HOSTS.items():
                helper = runtime / 'artifacts' / (host + '.zip')
                name = ('MCIconHelper/runtime/bin/java.exe' if os_name == 'windows' else
                        'MCIconHelper.app/Contents/runtime/Contents/Home/bin/java')
                with zipfile.ZipFile(helper, 'w') as archive:
                    info = zipfile.ZipInfo(name)
                    info.create_system = 3
                    info.external_attr = 0o100755 << 16
                    archive.writestr(info, b'synthetic-java')
                descriptor = payloads.candidate.encode({'host': {'os': os_name, 'arch': arch}, 'toolVersion': '1.1.0', 'worker': {'agentSha256': payloads.candidate.digest(worker)},
                    'artifacts': [{'id': host, 'role': 'host-helper', 'format': 'zip', 'bytes': helper.stat().st_size,
                                   'sha256': payloads.candidate.digest(helper)}]})
                path = 'descriptors/' + host + '.json'
                (runtime / path).write_bytes(descriptor)
                (runtime / (path + '.sig')).write_bytes(b'x' * 64)
                (prefix / 'descriptors').mkdir(exist_ok=True)
                (prefix / path).write_bytes(descriptor)
                (prefix / (path + '.sig')).write_bytes(b'x' * 64)
                entries.append({'host': {'os': os_name, 'arch': arch}, 'descriptorPath': path,
                                'descriptorSha256': payloads.hashlib.sha256(descriptor).hexdigest(), 'trustKeyPath': 'trust-set.bin'})
            (runtime / 'input.json').write_text(json.dumps({'finalBytesConfirmed': True, 'runtimes': entries}))
            (prefix / 'pins.json').write_text(json.dumps({'schemaVersion': 1, 'runtimes': entries}))
            core = root / 'core.jar'
            with zipfile.ZipFile(core, 'w') as jar:
                for p in prefix.rglob('*'):
                    if p.is_file():
                        jar.write(p, p.relative_to(resources).as_posix())
            shutil.copyfile(core, cli / 'core-1.1.0.jar')
            for name in ('cli', 'worker'):
                (cli / (name + '-1.1.0.jar')).write_bytes(b'synthetic-jar')
            args = SimpleNamespace(runtime_input=runtime, output=root / 'out', resources=resources, core=core,
                                   cli_lib=cli, worker=worker, sequence=1, openssl='openssl')
            with patch.object(payloads.trust_set, 'read', return_value=trust), patch.object(payloads, 'verify_signature') as verify:
                payloads.prepare(args)
                self.assertEqual(verify.call_count, 2)
            manifest = json.loads((args.output / 'stable.json').read_bytes())
            self.assertEqual(manifest['minimumBootstrapProtocol'], 4)
            for row in manifest['platforms']:
                archive = args.output / Path(row['payload']['url']).name
                self.assertEqual(payloads.candidate.digest(archive), row['payload']['sha256'])
                with zipfile.ZipFile(archive) as jar:
                    self.assertIn('app/worker/worker-agent.jar', jar.namelist())
                    java = next(n for n in jar.namelist() if n.startswith('java/bin/'))
                    self.assertTrue(jar.getinfo(java).external_attr >> 16 & 0o100)
            self.assertFalse((args.output / 'stable.json.sig').exists())


if __name__ == '__main__':
    unittest.main()
