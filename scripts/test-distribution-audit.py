#!/usr/bin/env python3
"""実配布候補を改変し、checksum・不足・資産混入・POM不一致を拒否することを確認する。"""
import argparse
import hashlib
import importlib.util
import io
import json
import shutil
import tempfile
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('distribution', Path(__file__).with_name('audit-distribution.py'))
distribution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribution)


def modify_zip(data, entry, replacement):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            if info.filename != entry:
                target.writestr(info, source.read(info.filename))
        if replacement is not None:
            target.writestr(entry, replacement)
    return output.getvalue()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('--version', required=True)
    a = p.parse_args()
    assert distribution.audit(a.directory, a.version)['passed']
    cli = f'mcicon-{a.version}.zip'
    library = f'mcicon-library-{a.version}.zip'
    root = f'mcicon-{a.version}/'
    libroot = f'mcicon-library-{a.version}/maven/io/github/rs0325/mcicon/mcicon-core/{a.version}/'
    tests = [('checksum', cli, None, None, 'SHA-256不一致'),
             ('game-asset', cli, root + 'assets/minecraft/textures/item/diamond.png', b'game asset', '配布対象外'),
             ('missing-docker', cli, root + 'docker/Dockerfile', None, 'CLI ZIPに不足'),
             ('missing-launcher', cli, root + 'bin/mcicon', None, 'CLI ZIPに不足'),
             ('missing-worker', cli, root + 'worker/worker-agent.jar', None, 'CLI ZIPに不足'),
             ('pom-version', library, libroot + f'mcicon-core-{a.version}.pom', 'pom', 'POMのversionが不一致'),
             ('game-class', cli, root + f'lib/core-{a.version}.jar', 'class', '配布対象外'),
             ('helper-class', cli, root + f'lib/core-{a.version}.jar', 'helper', '配布対象外'),
             ('qa-agent', cli, root + 'lib/fault-agent.jar', 'agent', '配布対象外'),
             ('fake-javadoc-image', library, libroot + f'mcicon-core-{a.version}-javadoc.jar', 'image', '配布対象外')]
    for name, archive, entry, replacement, expected in tests:
        with tempfile.TemporaryDirectory(prefix='mcicon-package-test-') as temporary:
            folder = Path(temporary) / 'release'
            shutil.copytree(a.directory, folder)
            path = folder / archive
            if name == 'checksum':
                with zipfile.ZipFile(path, 'a') as z:
                    z.comment = b'changed'
            else:
                if isinstance(replacement, str):
                    with zipfile.ZipFile(path) as z:
                        original = z.read(entry) if replacement != 'agent' else b''
                    if replacement == 'pom':
                        replacement = original.replace(('<version>' + a.version + '</version>').encode(), b'<version>wrong-version</version>')
                    elif replacement == 'agent':
                        output = io.BytesIO()
                        with zipfile.ZipFile(output, 'w') as agent:
                            agent.writestr('io/github/rs0325/mcicon/qa/ResourcePackFaultAgent.class', b'QA class')
                        replacement = output.getvalue()
                    else:
                        nested = {'class': 'net/minecraft/client/Minecraft.class',
                                  'helper': 'io/github/rs0325/mcicon/helper/LeaseFile.class',
                                  'image': 'resources/glass.png'}[replacement]
                        replacement = modify_zip(original, nested, b'not the documented bytes')
                path.write_bytes(modify_zip(path.read_bytes(), entry, replacement))
                sums = ''.join(hashlib.sha256(f.read_bytes()).hexdigest() + '  ' + f.name + '\n'
                               for f in sorted(folder.iterdir()) if f.name != 'SHA256SUMS')
                (folder / 'SHA256SUMS').write_text(sums, encoding='utf-8')
            result = distribution.audit(folder, a.version)
            assert not result['passed'] and any(expected in f for f in result['failures']), (name, result)
            print(name, 'PASS')
    print(json.dumps({'passed': True, 'cases': len(tests) + 1}))


if __name__ == '__main__':
    main()
