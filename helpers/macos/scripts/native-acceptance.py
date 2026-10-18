"""専用fixtureを準備し、別途許可された場合だけmacOS第1段VM受入を実行する。"""
from pathlib import Path
import argparse,datetime,fcntl,hashlib,json,os,shutil,stat,subprocess,time,uuid
if not __debug__:raise RuntimeError('Python最適化による検証省略は使用できません')
PREPARATION_END=time.monotonic()+300
BASE=(620224512,'1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55')
RENDERER=(593534813,'1dcf9b4363583ed6c9cf5847aaf5648aaaf80a084f978b075d1b4fe483ed2e70')
IMAGE='sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf'
JAVA=[('/opt/java/openjdk/bin/java','7380ce48ed5013735d2c8414db54adb8f981e7933ff594bd36f3baccddaafba3'),('/opt/java/java21/bin/java','2a207f5e7d075afa01d97f8048389a64432a44c4a5af0f5e77d6e286ec5f401d')]
def encoded(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
def sha(path):
 with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()
def save(path,value):
 with path.open('xb') as output:output.write(encoded(value)+b'\n')
 path.chmod(0o600)
def checked_copy(source,target,pin):
 if source.is_symlink() or not source.is_file() or source.stat().st_size!=pin[0]:raise RuntimeError('入力type/size不一致: '+str(source))
 target.parent.mkdir(parents=True,exist_ok=True);digest=hashlib.sha256()
 with source.open('rb') as incoming,target.open('xb') as output:
  while block:=incoming.read(1024*1024):
   if time.monotonic()>PREPARATION_END:raise RuntimeError('ローカル準備の300秒期限超過')
   digest.update(block);output.write(block)
  output.flush();os.fsync(output.fileno())
 if digest.hexdigest()!=pin[1]:raise RuntimeError('入力SHA不一致: '+str(source))
 target.chmod(0o600)
def prepare(a):
 output=a.output.absolute();output.mkdir(mode=0o700);output=output.resolve();root=output/'runtime';root.mkdir(mode=0o700);host=str(uuid.uuid4());(root/'host-id').write_text(host);(root/'jobs').mkdir();inventory={'fixtureOnly':True,'scope':'第1段control/diagnosticのみ、production inventory/trustではない'};inventory_bytes=encoded(inventory)
 helper=(a.helper_zip.stat().st_size,sha(a.helper_zip));descriptor={'schemaVersion':1,'runtimeId':'macos-native-fixture','toolVersion':'1.1.0','host':{'os':'macos','arch':'aarch64'},'minimumOsVersion':'15.7.7','helper':{'protocolVersion':1,'artifactId':'host','executable':'MCIconHelper.app/Contents/MacOS/MCIconHelper'},'worker':{'toolVersion':'1.1.0','agentSha256':'0'*64,'protocolVersion':1,'adapterSetSha256':'0'*64},'rendering':{'imageId':IMAGE,'environment':{},'javaExecutables':[{'executable':path,'sha256':digest} for path,digest in JAVA]},'artifacts':[]}
 for name,role,pin,format in [('host','host-helper',helper,'zip'),('base','guest-base',BASE,'qcow2'),('renderer','renderer-image',RENDERER,'tar.gz')]:
  descriptor['artifacts'].append({'id':name,'role':role,'url':'https://example.invalid/private-native-fixture/'+name,'bytes':pin[0],'sha256':pin[1],'unpackedBytes':pin[0],'format':format,'inventoryPath':'inventory/'+name+'.json','inventorySha256':hashlib.sha256(inventory_bytes).hexdigest(),'licenseFiles':[],'noticeFiles':[],'sourceArchives':[]})
 digest=hashlib.sha256(encoded(descriptor)).hexdigest();runtime=root/'runtimes'/descriptor['runtimeId']/digest
 checked_copy(a.base,runtime/'base.qcow2',BASE);checked_copy(a.renderer,runtime/'renderer.tar.gz',RENDERER)
 image=root/'helpers/macos-aarch64'/helper[1]/'MCIconHelper.app';record=json.loads(a.packaging.read_text());expected={row['path']:row for row in record['files']};actual={p.relative_to(a.image).as_posix() for p in a.image.rglob('*') if p.is_file()};assert actual==set(expected)
 for file in a.image.rglob('*'):
  if file.is_symlink() or not(file.is_file() or file.is_dir()):raise RuntimeError('helperにlink/特殊fileがあります')
 shutil.copytree(a.image,image)
 for name,row in expected.items():
  file=image/name
  if file.stat().st_size!=row['bytes'] or sha(file)!=row['sha256'] or file.stat().st_mode&0o777!=int(row['mode'],8):raise RuntimeError('helper tree不一致: '+name)
 save(output/'descriptor.json',descriptor);(output/'inventory.json').write_bytes(inventory_bytes)
 token=hashlib.sha256((str(root)+'\0'+host+'\0'+digest+'\0'+str(os.getuid())).encode()).hexdigest()[:20];anchor=Path('/private/tmp')/('mcicon-'+str(os.getuid())+'-'+token)
 if anchor.exists() or anchor.is_symlink():raise RuntimeError('予定anchorが既存です。上書きしません')
 process_source=output/'process-start.c';process_source.write_text('#include <libproc.h>\n#include <sys/proc_info.h>\n#include <stdlib.h>\n#include <stdio.h>\nint main(int n,char**v){struct proc_bsdinfo p;if(n!=2||proc_pidinfo(atoi(v[1]),PROC_PIDTBSDINFO,0,&p,sizeof(p))!=sizeof(p))return 1;printf("%llu\\n",p.pbi_start_tvsec*1000ULL+p.pbi_start_tvusec/1000ULL);return 0;}\n')
 subprocess.run(['/usr/bin/clang','-Wall','-Wextra','-Werror',str(process_source),'-o',str(output/'process-start')],check=True,timeout=30)
 plan={'schemaVersion':1,'status':'prepared-not-executed','scope':'第1段offline prepare/CPUID/image inspect/Java診断/通常releaseのみ','runtimeRoot':str(root),'entrypoint':str(image/'Contents/MacOS/MCIconHelper'),'descriptorSha256':digest,'hostId':host,'uid':os.getuid(),'anchor':str(anchor),'limaHome':str(anchor/'l'),'instance':'v','generationOwner':'helperが永続登録をforceする際UUID生成。実行結果で完全記録','registration':str(root/'macos'/(digest+'.json')),'results':str(output/'results'),'runnerSha256':sha(Path(__file__)),'processStartSha256':sha(output/'process-start'),'helperTree':record['files'],'fixedInputs':descriptor['artifacts'],'limits':{'totalSeconds':1800,'prepareSeconds':1200,'controlSeconds':30,'releaseSeconds':10,'vmCPUs':4,'containerMemoryMiB':4096,'vmMemoryMiB':9216,'diskGiB':24,'minimumFreeGiB':32},'internalSshKeys':str(anchor/'l')+'内でLimaが自動生成する接続認証keyのみ。秘密内容を証拠へ保存しない','unaccepted':['実VM未実行','renderer c498は私用監査候補。実Docker image-storeでindex/config ID同一性未確認','offline dpkg実依存閉包未確認','render/2job/取消/orphan/異常recovery/reboot/Intel/OS下限未受入','全license/source/production trust/最終descriptor受入は別gate']}
 save(output/'plan.json',plan);print(json.dumps({'plan':str(output/'plan.json'),'sha256':sha(output/'plan.json'),'anchor':str(anchor),'status':'no-vm-preparation-only'},ensure_ascii=False))
def existing():
 records={}
 for home in [Path.home()/'.lima',Path.home()/'.colima',Path.home()/'.docker']:
  if not home.exists():records[str(home)]={'absent':True};continue
  rows=[]
  for path in sorted(home.glob('*')):
   info=path.lstat();row={'name':path.name,'mode':stat.S_IFMT(info.st_mode)}
   if path.is_symlink():row['link']=os.readlink(path)
   elif path.is_dir():
    for name in ['lima.yaml','ha.pid','vz-identifier']:
     file=path/name
     if file.is_file() and not file.is_symlink():row[name]=sha(file)
   elif path.name=='config.json' and path.is_file():row['sha256']=sha(path)
   rows.append(row)
  records[str(home)]=rows
 return records
def execute(a):
 folder=a.plan.resolve().parent;plan=json.loads(a.plan.read_text());
 if sha(Path(__file__))!=plan['runnerSha256'] or sha(folder/'process-start')!=plan['processStartSha256']:raise RuntimeError('runner/probeが凍結時と不一致')
 root=Path(plan['runtimeRoot']);result=Path(plan['results']);result.mkdir(mode=0o700);deadline=time.monotonic()+plan['limits']['totalSeconds'];descriptor=(folder/'descriptor.json').read_bytes()
 if root.resolve()!=root or hashlib.sha256(descriptor.rstrip(b'\n')).hexdigest()!=plan['descriptorSha256'] or (root/'host-id').read_text()!=plan['hostId'] or os.getuid()!=plan['uid']:raise RuntimeError('凍結plan/root識別が不一致')
 if shutil.disk_usage(root).free<plan['limits']['minimumFreeGiB']*1024**3:raise RuntimeError('空き容量32GiB未満')
 anchor=Path(plan['anchor'])
 if anchor.exists() or anchor.is_symlink() or (root/'macos').exists():raise RuntimeError('VM登録/anchorが既存またはpartial。変更しない')
 previous=plan.get('previousReleasedJobs',[])
 if {p.name for p in (root/'jobs').iterdir()}!={row['jobId'] for row in previous}:raise RuntimeError('既知released job以外の履歴が存在する')
 for row in previous:
  job=root/'jobs'/row['jobId'];lease=json.loads((job/'lease.json').read_text())
  if job.is_symlink() or lease['state']!='released' or lease['resources']!={}:raise RuntimeError('過去jobはreleased空ではない')
  entries={p.relative_to(job).as_posix():p for p in job.rglob('*') if p.is_file()}
  if set(entries)!={p['path'] for p in row['files']} or any(p.is_symlink() for p in job.rglob('*')):raise RuntimeError('過去jobの保存集合が不一致')
  for file in row['files']:
   path=entries[file['path']]
   if path.stat().st_size!=file['bytes'] or sha(path)!=file['sha256']:raise RuntimeError('過去jobの保存bytesが不一致')
  if {p.name for p in (job/'tmp/macos-operations').iterdir()}!={'released.json'}:raise RuntimeError('過去jobに生成intentが存在する')
 for row in plan['helperTree']:
  path=Path(plan['entrypoint']).parents[2]/row['path']
  if path.is_symlink() or path.stat().st_size!=row['bytes'] or sha(path)!=row['sha256']:raise RuntimeError('helper treeが凍結時と不一致')
 start=int(subprocess.check_output([str(folder/'process-start'),str(os.getpid())],timeout=3));started=(datetime.datetime.fromtimestamp(start//1000,datetime.timezone.utc)+datetime.timedelta(milliseconds=start%1000)).isoformat(timespec='milliseconds').replace('+00:00','Z');job=str(uuid.uuid4());directory=root/'jobs'/job;directory.mkdir();temporary=directory/'tmp';temporary.mkdir();(directory/'inventory').mkdir()
 for artifact in json.loads(descriptor)['artifacts']:shutil.copyfile(folder/'inventory.json',directory/artifact['inventoryPath'])
 (directory/'descriptor.json').write_bytes(descriptor.rstrip(b'\n'));now=datetime.datetime.now(datetime.timezone.utc);identity={'schemaVersion':1,'jobId':job,'ownerId':str(uuid.uuid4()),'toolVersion':'1.1.0','workerSha256':'0'*64,'runtimeId':'macos-native-fixture','descriptorSha256':plan['descriptorSha256'],'hostId':plan['hostId'],'pid':os.getpid(),'processStartedAt':started,'createdAt':now.isoformat().replace('+00:00','Z'),'requirements':{'containerMemoryMiB':4096,'heapMemoryMiB':2048,'cpus':4,'preparationDeadline':(now+datetime.timedelta(seconds=1200)).isoformat().replace('+00:00','Z')}}
 lease={**identity,'state':'active','resources':{}};lease_path=directory/'lease.json';save(lease_path,lease);lock=directory/'lease.lock';lock.write_bytes(hashlib.sha256(encoded(identity)).hexdigest().encode());before=existing();save(result/'existing-before.json',before);records=[]
 unknown_child=False
 def call(name,operation,arguments=(),seconds=30,cleanup=False):
  nonlocal unknown_child
  timeout=min(seconds,max(0,deadline-time.monotonic()-1-(0 if cleanup else 15)))
  if timeout<=0:raise RuntimeError('試験全体期限超過')
  argv=[plan['entrypoint'],'--protocol','1','--root',str(root),'--lease',str(lease_path),operation]
  if arguments:argv+=['--',*arguments]
  record={'name':name,'argv':argv,'environmentKeys':['TMP','TEMP','TMPDIR'],'pid':None,'spawnedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'processStartedAtMillis':None,'exit':None,'timeout':timeout,'reaped':False};child=None;failure=None
  with (result/(name+'.stdout')).open('xb') as out,(result/(name+'.stderr')).open('xb') as err:
   try:
    child=subprocess.Popen(argv,env={key:str(temporary) for key in ['TMP','TEMP','TMPDIR']},stdout=out,stderr=err);record['pid']=child.pid
    save(result/(name+'.spawn.json'),record)
    record['processStartedAtMillis']=int(subprocess.check_output([str(folder/'process-start'),str(child.pid)],timeout=min(1,max(0.001,deadline-time.monotonic()))))
    save(result/(name+'.start.json'),record)
    record['exit']=child.wait(timeout=min(timeout,max(0.001,deadline-time.monotonic()-1-(0 if cleanup else 15))));record['reaped']=True
   except (Exception,KeyboardInterrupt) as interrupted:failure=type(interrupted).__name__+': '+str(interrupted)
   finally:
    if child is not None and not record['reaped']:
     try:
      child.kill();record['exit']=child.wait(timeout=max(0,min(1,deadline-time.monotonic())));record['reaped']=True
     except (Exception,KeyboardInterrupt) as unfinished:record['cleanupFailure']=type(unfinished).__name__+': '+str(unfinished);unknown_child=True
    record['failure']=failure;records.append(record);save(result/(name+'.json'),record)
  if failure is not None or not record['reaped'] or record['exit']!=0:raise RuntimeError(name+'失敗。未知資源は保持する')
  if operation!='docker' and json.loads((result/(name+'.stdout')).read_bytes())['status']!='ok':raise RuntimeError('control status不一致')
 error=None;released=False
 with lock.open('r+b') as receipt:
  fcntl.lockf(receipt,fcntl.LOCK_EX|fcntl.LOCK_NB,1,64)
  try:
   call('preflight','preflight')
   if not getattr(a,'preflight_only',False):
    call('prepare','prepare',seconds=1200);save(result/'prepared-lease.json',json.loads(lease_path.read_text()))
    registration=Path(plan['registration']);save(result/'registration.json',json.loads(registration.read_text()));save(result/'owner-marker.json',json.loads((anchor/'owner.json').read_text()))
    call('image-inspect','docker',['image','inspect',IMAGE,'--format','{{.Os}}/{{.Architecture}} {{.Id}}'])
    for index,(java,digest) in enumerate(JAVA):
     name='mcicon-doctor-'+str(uuid.uuid4());prefix=['run','--rm','--name',name,'--platform','linux/amd64','--network','none']
     call('java-version-'+str(index),'docker',prefix+['--entrypoint',java,IMAGE,'-version'])
     call('java-hash-'+str(index),'docker',prefix+['--entrypoint','sha256sum',IMAGE,java]);actual=(result/('java-hash-'+str(index)+'.stdout')).read_text().split()[0]
     if actual!=digest:raise RuntimeError('Java実hash不一致')
  except (Exception,KeyboardInterrupt) as failure:error=str(failure)
  finally:
   if unknown_child:error=(error+'; ' if error else '')+'自作成helperの終了未確認のためreleaseを重ねずactive保持'
   else:
    try:call('release','release',seconds=10,cleanup=True);current=json.loads(lease_path.read_text());assert current['resources']=={};current['state']='released';lease_path.write_bytes(encoded(current));released=True
    except Exception as failure:error=(error+'; ' if error else '')+'release: '+str(failure)
 after=existing();save(result/'existing-after.json',after);save(result/'result.json',{'status':('PASS-preflight-only' if getattr(a,'preflight_only',False) else 'PASS-stage1-only') if error is None and before==after else 'failed-or-unknown','error':error,'released':released,'existingUnchanged':before==after,'records':records,'limits':plan['unaccepted']});print(str(result/'result.json'))
 if error or before!=after:raise SystemExit(1)
if __name__=='__main__':
 p=argparse.ArgumentParser();sub=p.add_subparsers(dest='mode',required=True);prep=sub.add_parser('prepare');prep.add_argument('--image',type=Path,required=True);prep.add_argument('--packaging',type=Path,required=True);prep.add_argument('--helper-zip',type=Path,required=True);prep.add_argument('--base',type=Path,required=True);prep.add_argument('--renderer',type=Path,required=True);prep.add_argument('--output',type=Path,required=True);
 for name in ['execute-vm','preflight-only']:
  run=sub.add_parser(name);run.add_argument('--plan',type=Path,required=True)
 args=p.parse_args();args.preflight_only=args.mode=='preflight-only'
 if args.mode=='prepare':prepare(args)
 else:execute(args)
