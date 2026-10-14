#!/usr/bin/env python3
"""旧coreだけでcompileしたconsumerを新coreで実行する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--old-core',type=Path,required=True)
parser.add_argument('--old-sha256',required=True)
parser.add_argument('--report',type=Path,required=True)
args=parser.parse_args()
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
if digest(args.old_core)!=args.old_sha256:raise ValueError('旧core SHA-256不一致')
new=repo/'cli/build/install/mcicon/lib/core-0.1.0-SNAPSHOT.jar'
with tempfile.TemporaryDirectory(prefix='mcicon-binary-') as temporary:
    root=Path(temporary);old=root/'old-core.jar';shutil.copyfile(args.old_core,old)
    subprocess.run(['javac','--release','21','-cp',str(old),'-d',str(root),str(repo/'scripts/qa/WorkerOptionsBinaryConsumer.java')],check=True)
    consumer=root/'WorkerOptionsBinaryConsumer.class'
    result=subprocess.run(['java','-cp',str(root)+os.pathsep+str(new),'WorkerOptionsBinaryConsumer'],capture_output=True,text=True,timeout=10)
    if result.returncode:raise AssertionError(result.stderr)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps({'status':'PASS','oldCoreSha256':digest(old),'newCoreSha256':digest(new),'consumerSha256':digest(consumer),'constructorArguments':[5,6]},indent=2)+'\n')
    print(result.stdout.strip())
