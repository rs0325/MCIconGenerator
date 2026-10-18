"""実process/VMを使わず、runner自身のchild例外時の有限回収と証拠を確認する。"""
from pathlib import Path
import argparse,hashlib,json,runpy,subprocess,tempfile,types
from unittest.mock import patch
if not __debug__:raise RuntimeError('Python最適化設定は使えません')
p=argparse.ArgumentParser();p.add_argument('--runner',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir();module=runpy.run_path(str(a.runner));execute=module['execute'];records=[]
class Child:
 def __init__(self,interrupt,unconfirmed=False):self.pid=12345;self.interrupt=interrupt;self.unconfirmed=unconfirmed;self.waits=0;self.killed=False
 def wait(self,timeout):
  self.waits+=1
  if self.waits==1:
   if self.interrupt:raise KeyboardInterrupt()
   raise subprocess.TimeoutExpired('controlled-child',timeout)
  if self.unconfirmed:raise subprocess.TimeoutExpired('controlled-cleanup',timeout)
  return -9
 def kill(self):self.killed=True
class Release:
 pid=12346
 def wait(self,timeout):return 1
 def kill(self):raise AssertionError('完了済みreleaseをkillしない')
for name,interrupt,unconfirmed in [('interrupt',True,False),('timeout',False,False),('cleanup-unconfirmed',False,True)]:
 folder=Path(tempfile.mkdtemp(prefix=name+'-',dir=a.output)).resolve();root=folder/'runtime';root.mkdir();(root/'jobs').mkdir();host='00000000-0000-0000-0000-000000000001';(root/'host-id').write_text(host);descriptor=b'{"artifacts":[]}';(folder/'descriptor.json').write_bytes(descriptor);(folder/'inventory.json').write_text('{}');probe=folder/'process-start';probe.write_text('controlled-no-execution');results=folder/'results';plan={'runtimeRoot':str(root),'results':str(results),'runnerSha256':hashlib.sha256(a.runner.read_bytes()).hexdigest(),'processStartSha256':hashlib.sha256(probe.read_bytes()).hexdigest(),'descriptorSha256':hashlib.sha256(descriptor).hexdigest(),'hostId':host,'uid':__import__('os').getuid(),'anchor':str(folder/'never-created-anchor'),'helperTree':[],'entrypoint':str(folder/'never-executed-helper'),'limits':{'totalSeconds':30,'minimumFreeGiB':32},'unaccepted':['controlled Process only, no VM']};(folder/'plan.json').write_text(json.dumps(plan));child=Child(interrupt,unconfirmed);calls=[]
 def launch(argv,**kwargs):
  calls.append(argv)
  if len(calls)==1:return child
  if unconfirmed:raise AssertionError('未回収childへreleaseを重ねない')
  assert argv[-1]=='release';return Release()
 with patch('subprocess.Popen',side_effect=launch),patch('subprocess.check_output',return_value=b'1234\n'),patch('shutil.disk_usage',return_value=types.SimpleNamespace(free=40*1024**3)),patch.dict(execute.__globals__,{'existing':lambda:{}}):
  try:execute(types.SimpleNamespace(plan=folder/'plan.json'))
  except SystemExit as failure:assert failure.code==1
  else:raise AssertionError('故障fixtureを成功化した')
 spawn=json.loads((results/'preflight.spawn.json').read_text());started=json.loads((results/'preflight.start.json').read_text());final=json.loads((results/'preflight.json').read_text());outcome=json.loads((results/'result.json').read_text());assert spawn['pid']==child.pid and spawn['spawnedAt'] and started['processStartedAtMillis']==1234 and final['reaped']==(not unconfirmed) and child.killed;assert outcome['status']=='failed-or-unknown' and not outcome['released'];assert len(calls)==(1 if unconfirmed else 2);records.append({'name':name,'passed':True,'scope':'controlled Process injection only','spawnAndStartSaved':True,'childKillAttempted':True,'reaped':final['reaped'],'releaseCalls':len(calls)-1})
(a.output/'validation.json').write_text(json.dumps({'status':'PASS-fixture-only-no-process-no-vm','records':records},ensure_ascii=False,indent=2)+'\n');print(json.dumps(records,ensure_ascii=False))
