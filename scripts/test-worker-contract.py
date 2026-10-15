#!/usr/bin/env python3
"""ビルド済みCLI・JDK21とjsonschema 4.25.1でworker契約を確認する。実Dockerは使わない。"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from jsonschema import Draft202012Validator

repo=Path(__file__).resolve().parents[1]

class WorkerContractTest(unittest.TestCase):
    def test_schema_examples(self):
        schema=json.loads((repo/'schemas/worker-v1.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        validator=Draft202012Validator(schema)
        examples=json.loads((repo/'schemas/worker-v1.examples.json').read_text())
        for message in examples['accepted']:
            with self.subTest(message=message):self.assertTrue(validator.is_valid(message))
        for message in examples['rejected']:
            with self.subTest(message=message):self.assertFalse(validator.is_valid(message))

    def test_core_peer_mismatches(self):
        distribution=repo/'cli/build/install/mcicon'
        classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
        cases={'valid':'success','protocol-decimal':'success','worker-error':'MISSING_TEXTURE:3','request-id':'IOException',
               'response-type':'IOException','protocol-version':'IOException','ready-type':'IOException',
               'context':'WORKER_CONTEXT_MISMATCH:1','animation':'WORKER_CONTEXT_MISMATCH:1',
               'protocol-string':'IOException','protocol-fraction':'IOException','animation-string':'WORKER_CONTEXT_MISMATCH:1'}
        with tempfile.TemporaryDirectory(prefix='mcicon-contract-') as temporary:
            root=Path(temporary);classes=root/'classes';classes.mkdir()
            executable=root/'docker'
            executable.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
if 'inspect' in sys.argv:print('sha256:'+'0'*64)
elif 'run' in sys.argv and '--entrypoint' in sys.argv:print('openjdk version \"21.0.9\"')
elif 'run' in sys.argv:
 case=os.environ['MCICON_PROTOCOL_CASE']
 hello={'type':'ready','protocolVersion':1,'animationFrameZero':True,'contextProfile':'canonical-v1','renderer':'synthetic'}
 if case=='protocol-decimal':hello['protocolVersion']=1.0
 if case=='protocol-version':hello['protocolVersion']=2
 if case=='protocol-string':hello['protocolVersion']='1'
 if case=='protocol-fraction':hello['protocolVersion']=1.5
 if case=='ready-type':hello['type']='result'
 if case=='context':hello['contextProfile']='fixed-v1'
 if case=='animation':hello['animationFrameZero']=False
 if case=='animation-string':hello['animationFrameZero']='true'
 print(json.dumps(hello),flush=True)
 for line in sys.stdin:
  request=json.loads(line)
  with Path(os.environ['MCICON_PROTOCOL_CACHE'],'requests.jsonl').open('a') as output:output.write(json.dumps(request)+'\\n')
  response={'requestId':request['requestId'],'type':'result'}
  if request['command']=='catalog':
   response['entries']=[]
   if case=='request-id':response['requestId']='other-request'
   if case=='response-type':response['type']='ready'
   if case=='worker-error':response={'requestId':request['requestId'],'type':'error','code':'MISSING_TEXTURE','message':'合成欠損'}
  print(json.dumps(response),flush=True)
  if request['command']=='shutdown':break
elif 'rm' not in sys.argv:sys.exit(2)
''')
            executable.chmod(0o755)
            subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/WorkerContractProbe.java')],check=True)
            for case,outcome in cases.items():
                with self.subTest(case=case):
                    cache=root/case;cache.mkdir()
                    env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],MCICON_PROTOCOL_CASE=case,MCICON_PROTOCOL_CACHE=str(cache))
                    result=subprocess.run(['java','-cp',str(classes)+os.pathsep+classpath,'WorkerContractProbe',str(cache),str(distribution/'worker/worker-agent.jar'),outcome],env=env,text=True,capture_output=True,timeout=20)
                    self.assertEqual(result.returncode,0,result.stderr)
                    self.assertEqual(result.stdout.strip(),outcome)
                    if case in ['valid','protocol-decimal','worker-error','request-id','response-type']:
                        requests=[json.loads(line) for line in (cache/'requests.jsonl').read_text().splitlines()]
                        self.assertEqual([r['command'] for r in requests],['catalog','shutdown'])
                        self.assertNotEqual(requests[0]['requestId'],requests[1]['requestId'])

if __name__=='__main__':unittest.main()
