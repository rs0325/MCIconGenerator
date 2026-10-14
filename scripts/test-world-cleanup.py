#!/usr/bin/env python3
"""Dockerを模した子processでWorld自然終了後の所有回収を検証する。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--cache',type=Path,required=True)
p.add_argument('--compare',type=Path,required=True)
p.add_argument('--worker-jar',type=Path,required=True)
a=p.parse_args()
with tempfile.TemporaryDirectory(prefix='mcicon-world-cleanup-') as temporary:
 root=Path(temporary);docker=root/'docker'
 docker.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
state=Path(os.environ['WORLD_FAKE_STATE']);args=sys.argv[3:]
value=json.loads(state.read_text())
if args[:2]==['image','inspect']:print(args[2])
elif args[0]=='run':
 value['name']=args[args.index('--name')+1];value['image']=next(v for v in args if v.startswith('sha256:'))
 value['cmd']=args[args.index(value['image'])+1:];value['mounts']=[]
 for i,arg in enumerate(args):
  if arg=='-v':
   source,destination,*mode=args[i+1].split(':');value['mounts'].append({'Source':source,'Destination':destination})
 state.write_text(json.dumps(value));sys.exit(value['exit'])
elif args[0]=='ps':
 print('unrelated-container')
 if 'name' in value and not value.get('removed'):print(value['name'])
elif args[0]=='inspect':
 mounts=value['mounts']
 if value['mismatch']:mounts=[dict(v,Source='/unrelated') for v in mounts]
 print(json.dumps([{'Name':'/'+value['name'],'Image':value['image'],'Config':{'Cmd':value['cmd']},'Mounts':mounts}]))
elif args[:2]==['rm','-f']:
 value['removals']=args[2:];value['removed']=args[2:]==[value['name']];state.write_text(json.dumps(value));sys.exit(0 if value['removed'] else 9)
else:sys.exit(8)
''');docker.chmod(0o755)
 for exit_code,mismatch in [(0,False),(1,False),(0,True)]:
  state=root/('state-'+str(exit_code)+'-'+str(mismatch)+'.json');state.write_text(json.dumps({'exit':exit_code,'mismatch':mismatch}))
  out=root/state.stem;env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],WORLD_FAKE_STATE=str(state))
  result=subprocess.run(['python3',str(repo/'scripts/verify-world-inventory.py'),'--version','1.20.6','--ids','stone','--cache',str(a.cache),'--compare',str(a.compare),'--worker-jar',str(a.worker_jar),'--out',str(out),'--docker-context','synthetic','--docker-image','sha256:'+'1'*64],env=env,capture_output=True,text=True,timeout=30)
  report=json.loads((out/'report.json').read_text());value=json.loads(state.read_text())
  assert result.returncode!=0 and not report['passed'] and report['exitCode']==exit_code
  assert report['cleanupReason']=='natural-exit-container-remained'
  if mismatch:
   assert report['cleanupOwnershipMatched'] is False and not value.get('removals') and not report['ownedContainerAbsent']
  else:
   assert report['cleanupOwnershipMatched'] and report['cleanupExitCode']==0 and report['ownedContainerAbsentAfterCleanup'] and report['ownedContainerAbsent']
   assert value['removals']==[report['containerName']]
  print('自然終了'+str(exit_code)+'・所有'+str(not mismatch)+': PASS')
