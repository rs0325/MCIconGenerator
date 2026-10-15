#!/usr/bin/env python3
"""実atomicWrite中断のQA/coreを一度compileし、3ホスト共通の入力へ固定する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--distribution',type=Path,required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--javac',required=True)
args=parser.parse_args()
out=args.out.resolve();archive=out.with_suffix('.zip')
if out.exists() or archive.exists():parser.error('--outとZIPは新規の保存先が必要')
out.mkdir(parents=True);shutil.copytree(args.distribution/'lib',out/'lib')
(out/'classes').mkdir();(out/'source').mkdir()
qa=Path(__file__).resolve().parent/'qa'
for name in ('AtomicCrashProbe.java','HostAtomicRecoveryProbe.java'):
    shutil.copy2(qa/name,out/'source'/name)
shutil.copy2(qa/'run-host-atomic-windows.ps1',out/'run-windows.ps1')
classpath=os.pathsep.join(str(p) for p in sorted((out/'lib').glob('*.jar')))
subprocess.run([args.javac,'--release','21','-encoding','UTF-8','-cp',classpath,'-d',str(out/'classes'),*[str(p) for p in sorted((out/'source').glob('*.java'))]],check=True,timeout=60)
hashes={p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.rglob('*')) if p.is_file()}
(out/'file-hashes.json').write_text(json.dumps(hashes,indent=2)+'\n')
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as zipped:
    for p in sorted(out.rglob('*')):
        if p.is_file():zipped.write(p,p.relative_to(out).as_posix())
print(json.dumps({'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'inventorySha256':hashlib.sha256((out/'file-hashes.json').read_bytes()).hexdigest(),'windowsScriptSha256':hashes['run-windows.ps1'],'bundleHashes':hashes},indent=2))
