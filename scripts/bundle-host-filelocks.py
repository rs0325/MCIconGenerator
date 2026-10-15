#!/usr/bin/env python3
"""既存FileLock回帰を、同一core/fixtureでホスト検証するbundleへまとめる。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--classpath',type=Path,required=True)
parser.add_argument('--core-jar',type=Path,required=True)
parser.add_argument('--test-classes',type=Path,required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--javac',required=True)
args=parser.parse_args()
out=args.out.resolve()
archive_path=out.with_suffix('.zip')
if out.exists() or archive_path.exists():parser.error('--outとZIPは両方とも新規の保存先が必要')
out.mkdir(parents=True);lib=out/'lib';lib.mkdir();classes=out/'classes';classes.mkdir()
shutil.copy2(args.core_jar,lib/args.core_jar.name)
for item in json.loads(args.classpath.read_text()):
    path=Path(item)
    if path.suffix!='.jar':raise ValueError(f'想定外のruntime依存: {path}')
    destination=lib/path.name
    if destination.exists():raise ValueError(f'重複jar名: {path.name}')
    shutil.copy2(path,destination)
package=Path('io/github/rs0325/mcicon/internal')
target=classes/package;target.mkdir(parents=True)
for pattern in ('AssetStoreLockTest*.class','AssetLockProcess*.class'):
    sources=list((args.test_classes/package).glob(pattern))
    if not sources:raise ValueError(f'fixture未build: {pattern}')
    for source in sources:shutil.copy2(source,target/source.name)
classpath=os.pathsep.join(str(p) for p in sorted(lib.glob('*.jar')))
source=Path(__file__).parent/'qa/HostFileLockProbe.java'
subprocess.run([args.javac,'--release','21','-cp',classpath,'-d',str(classes),str(source)],check=True,timeout=60)
hashes={str(p.relative_to(out)).replace(os.sep,'/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.rglob('*')) if p.is_file()}
(out/'file-hashes.json').write_text(json.dumps(hashes,indent=2)+'\n')
with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(out.rglob('*')):
        if path.is_file():archive.write(path,str(path.relative_to(out)).replace(os.sep,'/'))
print(json.dumps({'files':len(hashes),'archive':str(archive_path),'archiveSHA256':hashlib.sha256(archive_path.read_bytes()).hexdigest(),'inventorySHA256':hashlib.sha256((out/'file-hashes.json').read_bytes()).hexdigest()}))
