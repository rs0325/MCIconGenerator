#!/usr/bin/env python3
"""配布候補のchecksum・構成・Maven座標・ゲーム資産の不混入を監査する。"""
import argparse
import hashlib
import io
import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

# JDK 21のjavadocに付属するUI画像。ゲーム由来のPNGとは区別する。
JAVADOC_IMAGES = {
    'resources/glass.png': '60d5f059f11c5f61f77f612a2c82d2a89eb6f25b19d68c51ae3e1d44ead7c1ca',
    'resources/x.png': '027f0ed28329634be645932e1ab7c321bf27b8cfaf9aed30f2cbf5ee4478952b',
}


def inspect_archive(data, name, failures):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.namelist()
        if len(entries) != len(set(entries)):
            failures.append(name + ': 重複したentry')
        for entry in entries:
            parts = PurePosixPath(entry).parts
            if entry.startswith('/') or '..' in parts or '\\' in entry:
                failures.append(name + ': 不正なentry ' + entry)
            documentation_image = (name.endswith('-javadoc.jar') and entry in JAVADOC_IMAGES and
                                   hashlib.sha256(archive.read(entry)).hexdigest() == JAVADOC_IMAGES[entry])
            if (entry.endswith(('.png', '.mcmeta', '.tiny')) and not documentation_image or
                    any(part in {'.local', '.mcicon-cache', 'natives-linux', 'objects'} for part in parts) or
                    entry.startswith(('net/minecraft/', 'com/mojang/', 'io/github/rs0325/mcicon/qa/')) or
                    '/assets/' in '/' + entry or '/scripts/qa/' in '/' + entry):
                failures.append(name + ': 配布対象外 ' + entry)
            if entry.endswith('.jar'):
                inspect_archive(archive.read(entry), name + '!' + entry, failures)
        return entries


def audit(directory, version):
    failures = []
    names = [f'mcicon-{version}.zip', f'mcicon-library-{version}.zip', f'worker-{version}-agent.jar']
    expected = set(names + ['SHA256SUMS'])
    if {p.name for p in directory.iterdir()} != expected:
        failures.append('配布ディレクトリに不足または余分なファイル')
    if not all((directory / name).is_file() for name in expected):
        return {'version': version, 'artifactsSha256': {}, 'failures': failures, 'passed': False}
    sums = {}
    for line in (directory / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        digest, name = line.split('  ', 1)
        if name in sums or name not in names:
            failures.append('SHA256SUMSの重複または未知のファイル: ' + name)
        sums[name] = digest
    if set(sums) != set(names):
        failures.append('SHA256SUMSの対象集合が不一致')
    hashes = {}
    for name in names:
        data = (directory / name).read_bytes()
        hashes[name] = hashlib.sha256(data).hexdigest()
        if hashes[name] != sums.get(name):
            failures.append(name + ': SHA-256不一致')
        inspect_archive(data, name, failures)

    common = ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'licenses/Apache-2.0.txt',
              'docs/usage.md', 'docs/compatibility.md', 'docs/contracts.md', 'docs/validation.md',
              'schemas/manifest-v1.schema.json', 'adapters/releases.json', 'examples/ExportExample.java',
              'docker/Dockerfile', 'docker/entrypoint.sh']
    with zipfile.ZipFile(directory / names[0]) as cli, zipfile.ZipFile(directory / names[1]) as library:
        root = 'mcicon-' + version + '/'
        for entry in common + ['README.md', 'bin/mcicon', 'bin/mcicon.bat', 'worker/worker-agent.jar',
                               'docker/Dockerfile', 'docker/entrypoint.sh', 'lib/core-' + version + '.jar']:
            if root + entry not in cli.namelist():
                failures.append('CLI ZIPに不足: ' + entry)
        if root + 'bin/mcicon' in cli.namelist() and not (cli.getinfo(root + 'bin/mcicon').external_attr >> 16) & 0o111:
            failures.append('POSIX launcherの実行権限がない')
        if (root + 'worker/worker-agent.jar' in cli.namelist() and
                hashlib.sha256(cli.read(root + 'worker/worker-agent.jar')).hexdigest() != hashes[names[2]]):
            failures.append('CLI内workerと単独workerが不一致')
        libroot = 'mcicon-library-' + version + '/'
        for entry in common:
            if libroot + entry not in library.namelist():
                failures.append('ライブラリZIPに不足: ' + entry)
        coordinate = libroot + 'maven/io/github/rs0325/mcicon/mcicon-core/' + version + '/'
        poms = [n for n in library.namelist() if n.startswith(coordinate) and n.endswith('.pom')]
        if len(poms) != 1:
            failures.append('対象版のPOMが1件ではない')
        else:
            pom = ET.fromstring(library.read(poms[0]))
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            for key, value in [('groupId', 'io.github.rs0325.mcicon'), ('artifactId', 'mcicon-core'), ('version', version)]:
                if pom.findtext('m:' + key, namespaces=ns) != value:
                    failures.append('POMの' + key + 'が不一致')
            artifacts = {d.findtext('m:artifactId', namespaces=ns) for d in pom.findall('m:dependencies/m:dependency', ns)}
            if artifacts != {'jackson-databind', 'mcicon-worker'}:
                failures.append('POMの依存関係が不一致')
        worker_coordinate = libroot + 'maven/io/github/rs0325/mcicon/mcicon-worker/' + version + '/'
        worker_jars = [n for n in library.namelist() if n.startswith(worker_coordinate) and n.endswith('.jar')]
        if len(worker_jars) != 3:
            failures.append('workerのbinary/sources/javadoc JARが不足または重複')
        wrapper = worker_coordinate + 'mcicon-worker-' + version + '.jar'
        if wrapper not in library.namelist():
            failures.append('worker wrapperが不足')
        else:
            with zipfile.ZipFile(io.BytesIO(library.read(wrapper))) as worker:
                agent_path = 'META-INF/mcicon/worker/agent.jar'
                metadata_path = 'META-INF/mcicon/worker/version.properties'
                if agent_path not in worker.namelist() or metadata_path not in worker.namelist():
                    failures.append('worker wrapperのresourceが不足')
                else:
                    agent = worker.read(agent_path)
                    if hashlib.sha256(agent).hexdigest() != hashes[names[2]]:
                        failures.append('wrapper内agentと単独workerが不一致')
                    expected = f'toolVersion={version}\nagentSha256={hashes[names[2]]}\n'.encode()
                    if worker.read(metadata_path) != expected:
                        failures.append('wrapperのmetadataが不一致')
                if any(n.endswith('.class') for n in worker.namelist()):
                    failures.append('wrapper外側にagent classが混入')
        jars = [n for n in library.namelist() if n.startswith(coordinate) and n.endswith('.jar')]
        if len(jars) != 3 or not any(n.endswith('-sources.jar') for n in jars) or not any(n.endswith('-javadoc.jar') for n in jars):
            failures.append('binary/sources/javadoc JARが不足または重複')
        binary = [n for n in jars if not n.endswith(('-sources.jar', '-javadoc.jar'))]
        if (len(binary) == 1 and root + 'lib/core-' + version + '.jar' in cli.namelist() and
                library.read(binary[0]) != cli.read(root + 'lib/core-' + version + '.jar')):
            failures.append('Maven binaryとCLI coreが不一致')
    return {'version': version, 'artifactsSha256': hashes, 'failures': failures, 'passed': not failures}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('--version', required=True)
    p.add_argument('--report', type=Path)
    a = p.parse_args()
    result = audit(a.directory, a.version)
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if a.report:
        a.report.write_text(output, encoding='utf-8')
    print(output, end='')
    return not result['passed']


if __name__ == '__main__':
    raise SystemExit(main())
