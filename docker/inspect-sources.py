"""固定renderer imageの文書とdpkg情報を、ネットワークなしで採取する。"""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess

IMAGE_ID = 'sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc'

PROBE = r'''
import base64
import hashlib
import json
import os
from pathlib import Path
import re

def record(path):
    raw = path.read_bytes()
    text = raw.decode('utf-8', errors='replace')
    return {'path': str(path), 'resolvedPath': str(path.resolve()),
            'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
            'bodyBase64': base64.b64encode(raw).decode('ascii'),
            'gplV2Mention': 'version 2' in text.lower() and 'general public license' in text.lower(),
            'classpathExceptionMention': bool(re.search(r'Classpath.*exception', text, re.I)),
            'markers': sorted(set(re.findall(r'(?im)^(?:SPDX-License-Identifier:|License:|Format:).*$', text)))}


packages = []
for block in Path('/var/lib/dpkg/status').read_text().split('\n\n'):
    fields = {}
    for line in block.splitlines():
        if line and not line[0].isspace() and ': ' in line:
            key, value = line.split(': ', 1)
            fields[key] = value
    if fields.get('Status') != 'install ok installed':
        continue
    name = fields['Package']
    copyright = Path('/usr/share/doc') / name / 'copyright'
    packages.append({'package': name, 'version': fields['Version'],
                     'architecture': fields['Architecture'],
                     'sourceField': fields.get('Source'),
                     'copyright': record(copyright) if copyright.is_file() else None})
java = []
for home in sorted(Path('/opt/java').iterdir()) if Path('/opt/java').is_dir() else []:
    if not home.is_dir():
        continue
    release = home / 'release'
    docs = []
    for path in sorted(home.rglob('*')):
        if path.is_file() and (path.name in ('LICENSE', 'NOTICE', 'ASSEMBLY_EXCEPTION',
                                             'THIRD_PARTY_README', 'release')
                               or 'legal' in path.relative_to(home).parts):
            docs.append(record(path))
    java.append({'home': str(home), 'release': release.read_text() if release.is_file() else None,
                 'documents': docs})
paths = []
for directory, dirs, files in os.walk('/'):
    dirs[:] = sorted(d for d in dirs if str(Path(directory) / d) not in ('/proc', '/sys', '/dev'))
    paths.extend(str(Path(directory) / f) for f in sorted(files))
pattern = r'(?i)(minecraft|mojang|lwjgl|/\.minecraft(?:/|$)|(?:^|/)(?:client|server|launcher)\.jar$|/assets/(?:indexes|objects)/)'
print(json.dumps({'packages': sorted(packages, key=lambda p: p['package']), 'java': java,
    'assetSearch': {'pattern': pattern, 'excludedMounts': ['/proc', '/sys', '/dev'],
                    'fileCount': len(paths), 'matches': [p for p in paths if re.search(pattern, p)],
                    'jarsOutsideJdk': [p for p in paths if p.endswith('.jar') and not p.startswith('/opt/java/')],
                    'workspaceEntries': sorted(os.listdir('/workspace')) if Path('/workspace').is_dir() else []},
    'commonLicenses': [record(p) for p in sorted(Path('/usr/share/common-licenses').iterdir()) if p.is_file()],
    'fileList': sorted(paths)}, ensure_ascii=False))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default=IMAGE_ID)
    parser.add_argument('--expected-image', default=IMAGE_ID, help='照合する固定image ID')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    actual = subprocess.check_output(
        ['docker', 'image', 'inspect', args.image, '--format', '{{.Id}}'], text=True).strip()
    if actual != args.expected_image:
        raise ValueError('固定image IDと一致しません')
    raw = subprocess.check_output(
        ['docker', 'run', '--rm', '--network', 'none', '--read-only',
         '--entrypoint', 'python3', actual, '-c', PROBE])
    evidence = json.loads(raw)
    files = ('\n'.join(evidence.pop('fileList')) + '\n').encode('utf-8')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'image-files.txt').write_bytes(files)
    documents = [p['copyright'] for p in evidence['packages'] if p['copyright']]
    documents += [d for j in evidence['java'] for d in j['documents']]
    documents += evidence['commonLicenses']
    for document in documents:
        target = args.output / 'image-documents' / document['path'].lstrip('/')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(document.pop('bodyBase64')))
    evidence['imageId'] = actual
    evidence['fileList'] = {'path': 'image-files.txt', 'bytes': len(files),
                            'sha256': hashlib.sha256(files).hexdigest()}
    (args.output / 'image-evidence.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'packages': len(evidence['packages']),
        'copyrightMissing': [p['package'] for p in evidence['packages'] if not p['copyright']],
        'javaHomes': [j['home'] for j in evidence['java']],
        'assetSearch': evidence['assetSearch']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
