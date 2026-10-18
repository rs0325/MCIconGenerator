#!/usr/bin/env python3
"""Central候補のPOM・推移依存・checksum・署名file構成をローカル監査する。"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


def audit(bundle, version, require_signatures=False):
    failures = []
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            failures.append('ZIP entryが重複')
        if any(name.startswith('/') or '..' in name.split('/') or '\\' in name for name in names):
            failures.append('ZIP entryに不正なpath')
        files = {name for name in names if not name.endswith('/')}
        expected = set()
        for artifact in ('mcicon-core', 'mcicon-worker'):
            base = f'io/github/rs0325/mcicon/{artifact}/{version}/{artifact}-{version}'
            primary = [base + suffix for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module')]
            for name in primary:
                expected.add(name)
                if name not in files:
                    failures.append('artifactが不足: ' + name)
                    continue
                data = archive.read(name)
                if name.endswith('.jar'):
                    with zipfile.ZipFile(io.BytesIO(data)) as jar:
                        if any(entry.startswith('io/github/rs0325/mcicon/helper/') for entry in jar.namelist()):
                            failures.append('helper内部moduleが公開JARへ混入: ' + name)
                for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                    checksum = name + '.' + algorithm
                    expected.add(checksum)
                    if checksum not in files or archive.read(checksum).decode('ascii').strip() != hashlib.new(algorithm, data).hexdigest():
                        failures.append('checksum不一致または不足: ' + checksum)
                signature = name + '.asc'
                if signature in files:
                    expected.add(signature)
                    if not archive.read(signature).startswith(b'-----BEGIN PGP SIGNATURE-----'):
                        failures.append('署名fileの形式が不正: ' + signature)
                    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                        checksum = signature + '.' + algorithm
                        if checksum in files:
                            expected.add(checksum)
                            if archive.read(checksum).decode('ascii').strip() != hashlib.new(algorithm, archive.read(signature)).hexdigest():
                                failures.append('署名checksum不一致: ' + checksum)
                elif require_signatures:
                    failures.append('署名が不足: ' + signature)
            if base + '.pom' not in files:
                continue
            pom = ET.fromstring(archive.read(base + '.pom'))
            for key, value in [('groupId', 'io.github.rs0325.mcicon'), ('artifactId', artifact), ('version', version)]:
                if pom.findtext('m:' + key, namespaces=ns) != value:
                    failures.append(artifact + ': 座標不一致 ' + key)
            for field in ('name', 'description', 'url', 'licenses/license/name', 'licenses/license/url',
                          'developers/developer/id', 'developers/developer/name', 'scm/url', 'scm/connection', 'scm/developerConnection'):
                if not pom.findtext('/'.join('m:' + part for part in field.split('/')), namespaces=ns):
                    failures.append(artifact + ': POM情報不足 ' + field)
            dependencies = pom.findall('m:dependencies/m:dependency', ns)
            ids = {d.findtext('m:artifactId', namespaces=ns) for d in dependencies}
            if ids != ({'jackson-databind', 'mcicon-worker', 'commons-compress', 'xz'} if artifact == 'mcicon-core' else set()):
                failures.append(artifact + ': 依存集合不一致')
            for dependency in dependencies:
                if dependency.findtext('m:artifactId', namespaces=ns) == 'mcicon-worker':
                    if dependency.findtext('m:version', namespaces=ns) != version or dependency.findtext('m:scope', namespaces=ns) != 'runtime':
                        failures.append('worker推移依存の版またはscopeが不一致')
            if artifact == 'mcicon-worker' and base + '.jar' in files:
                with zipfile.ZipFile(io.BytesIO(archive.read(base + '.jar'))) as worker:
                    agent = worker.read('META-INF/mcicon/worker/agent.jar')
                    expected_metadata = f'toolVersion={version}\nagentSha256={hashlib.sha256(agent).hexdigest()}\n'.encode()
                    if worker.read('META-INF/mcicon/worker/version.properties') != expected_metadata:
                        failures.append('worker metadata不一致')
                    if any(name.endswith('.class') for name in worker.namelist()):
                        failures.append('wrapperに外側classが混入')
        if files != expected:
            failures.append('bundleに未知のfile: ' + ', '.join(sorted(files - expected)))
    return {'version': version, 'bundle': str(bundle), 'signatureFilesRequired': require_signatures,
            'cryptographicSignatureVerification': '未実施。公開鍵だけを指定したgpgvとCentral検証が別途必要',
            'failures': failures, 'passed': not failures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--require-signatures', action='store_true')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', args.version):
        parser.error('版の形式が不正')
    result = audit(args.bundle, args.version, args.require_signatures)
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.report:
        args.report.write_text(output, encoding='utf-8')
    print(output, end='')
    return not result['passed']


if __name__ == '__main__':
    raise SystemExit(main())
