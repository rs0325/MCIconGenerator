#!/usr/bin/env python3
"""Synthetic RGBA8 GPU blending QA against the production alpha reconstruction.

Uses cached LWJGL 3.3.3 and local Docker. This does not certify game color space,
special render layers, glint, or GPU/driver independence.
"""
import argparse
import json
from pathlib import Path
import subprocess

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--cache',type=Path,default=repo/'.local/cache')
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
cache=args.cache.resolve();out=args.out.resolve()
out.mkdir(parents=True,exist_ok=True);classes=out/'classes';classes.mkdir(exist_ok=True)
libraries=[]
for name in ('lwjgl','lwjgl-glfw','lwjgl-opengl'):
    folder=cache/'libraries/org/lwjgl'/name/'3.3.3'
    for suffix in ('','-natives-linux'):
        path=folder/(name+'-3.3.3'+suffix+'.jar')
        if not path.is_file():parser.error('Prepare Minecraft 1.21.4 cache first: '+str(path))
        libraries.append(path)
agent=repo/'worker/build/libs/worker-0.1.0-SNAPSHOT-agent.jar'
classpath=':'.join(str(path) for path in [agent,*libraries])
subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/NativeAlphaProbe.java')],check=True)
container_classpath=':'.join(['/qa/classes','/worker.jar',*('/cache/'+str(path.relative_to(cache)) for path in libraries)])
result=subprocess.run(['docker','--context',args.docker_context,'run','--rm','--network','none','--platform','linux/amd64',
    '-v',str(cache)+':/cache:ro','-v',str(out)+':/qa','-v',str(agent)+':/worker.jar:ro',args.docker_image,
    '/opt/java/java21/bin/java','-Djava.awt.headless=true','-cp',container_classpath,
    'io.github.rs0325.mcicon.worker.NativeAlphaProbe','/qa/synthetic.png'],capture_output=True,text=True,timeout=60)
(out/'probe.log').write_text(result.stderr)
assert result.returncode==0,(result.stdout,result.stderr)
report=json.loads(result.stdout)
report.update(gameColorSpaceCertified=False,synthetic=True)
(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
