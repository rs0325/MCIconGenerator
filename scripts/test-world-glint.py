#!/usr/bin/env python3
"""独立QAのglint置換を合成し、任意で検証済み12版cacheの元bytecodeにも照合する。"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cache',type=Path,help='元clientとmappingsを含む検証済み専用cache')
parser.add_argument('--worker-jar',type=Path,default=repo/'cli/build/install/mcicon/worker/worker-agent.jar')
args=parser.parse_args()
with tempfile.TemporaryDirectory(prefix='mcicon-glint-test-') as temporary:
    classes=Path(temporary)
    subprocess.run(['javac','--release','8','-cp',str(args.worker_jar),'-d',str(classes),str(repo/'scripts/qa/WorldInventoryAgent.java'),str(repo/'scripts/qa/WorldGlintProbe.java')],check=True)
    command=['java','-cp',str(classes)+os.pathsep+str(args.worker_jar),'io.github.rs0325.mcicon.qa.WorldGlintProbe']
    if args.cache:command.append(str(args.cache.resolve()))
    subprocess.run(command,check=True,timeout=30)
