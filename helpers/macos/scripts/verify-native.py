"""VZを起動せず、native guardのstdioと親強制終了時の所有group回収を検証する。"""
import argparse,json,os,signal,subprocess,tempfile,time
from pathlib import Path
if not __debug__:raise RuntimeError("検証を省略するPython最適化設定は使用できません")
parser=argparse.ArgumentParser();parser.add_argument('--native',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True);guard=args.output/'process-guard'
subprocess.run(['/usr/bin/clang','-std=c17','-O2','-Wall','-Wextra','-Werror',str(args.native/'process-guard.c'),'-o',str(guard)],check=True,timeout=30)
root=Path(tempfile.mkdtemp(prefix='native-fixture-',dir=args.output)).resolve();records=[]
def command(name,*argv):
 return [str(guard.resolve()),'--completion',str(root/(name+'.done')),'--intent-sha','a'*64,'--',*argv]
r=subprocess.run(command('stdio','/bin/sh','-c','/bin/cat; echo guard-stderr >&2; exit 7'),input='日本語 stdin\n',capture_output=True,text=True,env={'TMPDIR':str(root)},timeout=10)
assert r.returncode==7 and r.stdout=='日本語 stdin\n' and r.stderr=='guard-stderr\n';records.append({'name':'stdio-exit','passed':True,'exit':r.returncode})
marker=root/'owned-pids';script=root/'client.sh';script.write_text('#!/bin/sh\necho $$ > "$1"\n/bin/sleep 60 &\necho $! >> "$1"\nwait\n');script.chmod(0o700)
wrapper=root/'wrapper.py';wrapper.write_text('import subprocess,sys\np=subprocess.Popen(sys.argv[1:])\nprint(p.pid,flush=True)\np.wait()\n')
process=subprocess.Popen([os.sys.executable,str(wrapper),*command('parent',str(script),str(marker))],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
try:
 deadline=time.monotonic()+5
 while not marker.exists() or len(marker.read_text().splitlines())!=2:
  if time.monotonic()>deadline:raise RuntimeError('client開始期限超過')
  time.sleep(0.01)
 guard_pid=int(process.stdout.readline());owned=[int(x) for x in marker.read_text().splitlines()];process.kill();process.wait(timeout=3)
 deadline=time.monotonic()+5
 def alive(pid):
  try:os.kill(pid,0);return True
  except ProcessLookupError:return False
 while any(alive(pid) for pid in owned):
  if time.monotonic()>deadline:raise RuntimeError('owned client回収期限超過')
  time.sleep(0.01)
 assert (root/'parent.done').read_text()=='a'*64+'\n'
 records.append({'name':'parent-sigkill','passed':True,'guardPid':guard_pid,'ownedPids':owned,'wrapperReaped':process.poll() is not None})
finally:
 if process.poll() is None:process.kill();process.wait(timeout=3)
 process.stdout.close();process.stderr.close()
assert (root/'stdio.done').read_text()=='a'*64+'\n'
# 異なるintentの完了印を上書きしない。
collision=root/'collision.done';collision.write_text('preserved')
r=subprocess.run(command('collision','/usr/bin/true'),capture_output=True,timeout=5)
assert r.returncode==1 and collision.read_text()=='preserved'
link=root/'link.done';link.symlink_to(collision)
r=subprocess.run(command('link','/usr/bin/true'),capture_output=True,timeout=5)
assert r.returncode==1 and collision.read_text()=='preserved';records.append({'name':'completion-collision-link','passed':True})
# 先在pendingは通常fileでもlinkでも触らず、finalを公開しない。
for name,linked in [('pending-collision',False),('pending-link',True)]:
 pending=root/(name+'.done.pending')
 if linked:pending.symlink_to(collision)
 else:pending.write_text('preserved')
 r=subprocess.run(command(name,'/usr/bin/true'),capture_output=True,timeout=5)
 assert r.returncode==1 and not (root/(name+'.done')).exists() and pending.read_text()=='preserved'
records[-1]['pendingCollisionAndLink']=True
# 本物のPID/startを持つcore代役を生成guardだけが監視する。
owner_source=root/'owner.c';owner_source.write_text('#include <sys/proc_info.h>\n#include <libproc.h>\n#include <unistd.h>\n#include <stdio.h>\nint main(void){struct proc_bsdinfo p;if(proc_pidinfo(getpid(),PROC_PIDTBSDINFO,0,&p,sizeof(p))!=sizeof(p))return 1;printf("%d %llu\\n",getpid(),p.pbi_start_tvsec*1000ULL+p.pbi_start_tvusec/1000ULL);fflush(stdout);sleep(30);return 0;}\n')
owner_binary=root/'owner';subprocess.run(['/usr/bin/clang',str(owner_source),'-o',str(owner_binary)],check=True,timeout=30)
owner=subprocess.Popen([str(owner_binary)],stdout=subprocess.PIPE,text=True)
owner_pid,started=owner.stdout.readline().split();marker2=root/'owner-client-pids'
argv=command('owner',str(script),str(marker2));separator=argv.index('--');argv[separator:separator]=['--owner',owner_pid,started]
client=subprocess.Popen(argv,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
try:
 deadline=time.monotonic()+5
 while not marker2.exists() or len(marker2.read_text().splitlines())!=2:
  if time.monotonic()>deadline:raise RuntimeError('owner client開始期限超過')
  time.sleep(0.01)
 owned2=[int(x) for x in marker2.read_text().splitlines()];owner.kill();owner.wait(timeout=3);client.wait(timeout=5)
 assert (root/'owner.done').read_text()=='a'*64+'\n'
 assert not any(alive(pid) for pid in owned2);records.append({'name':'owner-death','passed':True,'ownedPids':owned2})
finally:
 if owner.poll() is None:owner.kill();owner.wait(timeout=3)
 if client.poll() is None:client.terminate();client.wait(timeout=5)
 owner.stdout.close();client.stdout.close();client.stderr.close()
# guard自身の異常は完了印を残さない。fixtureが保有するclientを明示回収する。
marker3=root/'guard-client-pids';client=subprocess.Popen(command('guard-killed',str(script),str(marker3)),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
try:
 deadline=time.monotonic()+5
 while not marker3.exists() or len(marker3.read_text().splitlines())!=2:
  if time.monotonic()>deadline:raise RuntimeError('guard kill fixture開始期限超過')
  time.sleep(0.01)
 owned3=[int(x) for x in marker3.read_text().splitlines()];client.kill();client.wait(timeout=3)
 assert not (root/'guard-killed.done').exists();records.append({'name':'guard-sigkill-unconfirmed','passed':True,'ownedPids':owned3})
finally:
 if client.poll() is None:client.terminate();client.wait(timeout=5)
 if 'owned3' in locals():
  for pid in reversed(owned3):
   try:os.kill(pid,signal.SIGKILL)
   except ProcessLookupError:pass
 client.stdout.close();client.stderr.close()
(args.output/'native-validation.json').write_text(json.dumps({'status':'fixture-only-no-vm','records':records,'limits':['guard自身SIGKILL後の回収は保証しない','別process groupへ離脱したLima hostagentはVM所有記録で別途回収','VZ/VM起動・実Docker・Intelの成功ではない']},ensure_ascii=False,indent=2)+'\n');print(json.dumps(records,ensure_ascii=False))
