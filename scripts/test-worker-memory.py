#!/usr/bin/env python3
"""ビルド済みCLI/JDK21でメモリ予算の起動引数を合成確認する。実Dockerは使用しない。"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

repo=Path(__file__).resolve().parents[1]

class WorkerMemoryTest(unittest.TestCase):
    def test_container_and_heap_arguments_are_exact(self):
        distribution=repo/'cli/build/install/mcicon'
        classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
        with tempfile.TemporaryDirectory(prefix='mcicon-memory-') as temporary:
            root=Path(temporary);classes=root/'classes';classes.mkdir()
            executable=root/'docker'
            executable.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
with Path(os.environ['MCICON_MEMORY_CACHE'],'docker.jsonl').open('a') as log:log.write(json.dumps(sys.argv[1:])+'\\n')
if 'inspect' in sys.argv:print('sha256:'+'0'*64)
elif 'run' in sys.argv and '--entrypoint' in sys.argv:print('openjdk version "21.0.9"')
elif 'run' in sys.argv:
 print(json.dumps({'type':'ready','protocolVersion':1,'animationFrameZero':True,'contextProfile':'canonical-v1','renderer':'synthetic'}),flush=True)
 for line in sys.stdin:
  request=json.loads(line);response={'type':'result','requestId':request['requestId']}
  if request['command']=='catalog':response['entries']=[]
  print(json.dumps(response),flush=True)
  if request['command']=='shutdown':break
elif 'rm' not in sys.argv:sys.exit(2)
''')
            executable.chmod(0o755)
            subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/WorkerMemoryProbe.java')],check=True)
            for label,pair in [('default',None),('custom',(4096,2560)),('small',(2,1))]:
                with self.subTest(label=label):
                    cache=root/label;cache.mkdir()
                    env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],MCICON_MEMORY_CACHE=str(cache))
                    command=['java','-cp',str(classes)+os.pathsep+classpath,'WorkerMemoryProbe',str(cache),str(distribution/'worker/worker-agent.jar')]
                    if pair:command+=list(map(str,pair))
                    result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=20)
                    self.assertEqual(result.returncode,0,result.stderr)
                    calls=[json.loads(line) for line in (cache/'docker.jsonl').read_text().splitlines()]
                    diagnostics=[args for args in calls if 'run' in args and '--entrypoint' in args]
                    self.assertEqual(len(diagnostics),1)
                    self.assertNotIn('--memory',diagnostics[0])
                    self.assertFalse(any(arg.startswith('-Xmx') for arg in diagnostics[0]))
                    worker=[args for args in calls if 'run' in args and '--entrypoint' not in args]
                    self.assertEqual(len(worker),1)
                    args=worker[0];memory,heap=pair or (3072,2048)
                    self.assertEqual(args[args.index('--memory')+1],str(memory)+'m')
                    self.assertIn('-Xmx'+str(heap)+'m',args)
                    self.assertEqual(args[args.index('--cpus')+1],'4')

if __name__=='__main__':unittest.main()
