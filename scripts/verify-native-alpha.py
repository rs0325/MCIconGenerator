#!/usr/bin/env python3
"""Synthetic RGBA8 GPU blending QA against the production alpha reconstruction.

Uses cached LWJGL 3.3.3 and local Docker. This does not certify game color space,
special render layers, glint, or GPU/driver independence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--cache',type=Path,default=repo/'.local/cache')
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
cache=args.cache.resolve();out=args.out.resolve()
out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir()
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
image=subprocess.run(['docker','--context',args.docker_context,'image','inspect',args.docker_image,'--format','{{.Id}}'],capture_output=True,text=True,check=True).stdout.strip()
container='mcicon-worker-color-probe-'+str(uuid.uuid4())
result=subprocess.run(['docker','--context',args.docker_context,'run','--rm','--name',container,'--network','none','--platform','linux/amd64',
    '-v',str(cache)+':/cache:ro','-v',str(out)+':/qa','-v',str(agent)+':/worker.jar:ro',image,
    '/opt/java/java21/bin/java','-Djava.awt.headless=true','-cp',container_classpath,
    'io.github.rs0325.mcicon.worker.NativeAlphaProbe','/qa/synthetic.png'],capture_output=True,text=True,timeout=60)
(out/'probe.log').write_text(result.stderr)
(out/'probe.stdout.json').write_text(result.stdout)
assert result.returncode==0,(result.stdout,result.stderr)
report=json.loads(result.stdout)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
report.update(gameColorSpaceCertified=False,synthetic=True,imageId=image,productionAgentSha256=sha(agent),harnessSourcesSha256={str(f.relative_to(repo)):sha(f) for f in [Path(__file__).resolve(),repo/'scripts/qa/NativeAlphaProbe.java']},libraryHashes=[{'path':str(f.relative_to(cache)),'sha256':sha(f)}for f in libraries],pngHashes=[{'path':f.name,'sha256':sha(f)}for f in sorted(out.glob('*.png'))])
(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
