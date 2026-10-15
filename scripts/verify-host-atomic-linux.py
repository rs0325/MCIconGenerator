#!/usr/bin/env python3
"""固定imageの専用tmpfsで共通atomic bundleを実行し、所有containerだけを回収する。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid
import zipfile

from qa.owned_container import cleanup

parser=argparse.ArgumentParser()
parser.add_argument('--bundle',type=Path,required=True)
parser.add_argument('--archive',type=Path,required=True)
parser.add_argument('--archive-sha256',required=True)
parser.add_argument('--inventory-sha256',required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--docker-context',default='colima-mcicon')
args=parser.parse_args()
image='sha256:c498eda74b68a67acb8709951f36b294dcef9025acc480238e7742bf2b9601cf'
bundle=args.bundle.resolve();out=args.out.resolve()
if out.exists():parser.error('--outは新規directoryが必要')
if hashlib.sha256(args.archive.read_bytes()).hexdigest()!=args.archive_sha256:parser.error('archive SHA256不一致')
if hashlib.sha256((bundle/'file-hashes.json').read_bytes()).hexdigest()!=args.inventory_sha256:parser.error('inventory SHA256不一致')
with zipfile.ZipFile(args.archive) as archive:
    names=archive.namelist()
    actual={p.relative_to(bundle).as_posix() for p in bundle.rglob('*') if p.is_file()}
    if len(names)!=len(set(names)) or set(names)!=actual:parser.error('archiveと展開bundleの集合不一致')
    for name in names:
        if archive.read(name)!=(bundle/name).read_bytes():parser.error('archiveと展開bundleのbytes不一致')
out.mkdir(parents=True);reports=out/'reports';reports.mkdir()
owner=str(uuid.uuid4());name='mcicon-atomic-'+owner
docker=['docker','--context',args.docker_context]
result={'passed':False,'image':image,'archiveSha256':args.archive_sha256,'inventorySha256':args.inventory_sha256,'containerName':name,'owner':owner}
mounts={'/input':(bundle,False),'/report':(reports,True)}

def run(arguments,timeout=20):
    return subprocess.run(docker+arguments,capture_output=True,text=True,timeout=timeout)

try:
    launched=run(['run','-d','--init','--name',name,'--label','mcicon.qa.owner='+owner,'--network','none','--platform','linux/amd64',
                  '--memory','1g','--cpus','2','--pids-limit','64','--tmpfs','/output:rw,size=512m',
                  '-v',str(bundle)+':/input:ro','-v',str(reports)+':/report:rw','-w','/input','--entrypoint','/opt/java/java21/bin/java',
                  image,'-Xmx128m','-cp','classes:lib/*','HostAtomicRecoveryProbe','/output/job','/report/evidence','75','normal',args.inventory_sha256])
    result['launchExit']=launched.returncode
    (out/'launch.log').write_text(launched.stdout+launched.stderr)
    if launched.returncode!=0:raise RuntimeError('container起動失敗')
    inspected=run(['inspect',name]);container=json.loads(inspected.stdout)[0]
    if inspected.returncode!=0 or container['Image']!=image:raise RuntimeError('container image不一致')
    result['containerId']=container['Id']
    waited=run(['wait',name],90);(out/'wait.log').write_text(waited.stdout+waited.stderr)
    if waited.returncode!=0:raise RuntimeError('container終了待機失敗')
    result['containerExit']=int(waited.stdout.strip())
    state=json.loads(run(['inspect',name]).stdout)[0]['State'];result['oomKilled']=state['OOMKilled']
    logs=run(['logs',name]);(out/'supervisor.log').write_text(logs.stdout+logs.stderr)
    copied=run(['cp',name+':/output/.',str(out/'exports')],30);result['outputCopyExit']=copied.returncode
    if copied.returncode!=0:raise RuntimeError('raw出力取得失敗')
    report=json.loads((reports/'evidence/report.json').read_text());result['hostReport']=report
    result['passed']=result['containerExit']==0 and not state['OOMKilled'] and report['passed'] and not report['remainingOwnedActors']
except Exception as error:
    result['failure']=str(error)
finally:
    # imageまで一致した個体だけを既存のname/owner/mount照合へ渡す。
    try:
        inspected=run(['inspect',name]);containers=json.loads(inspected.stdout) if inspected.returncode==0 else []
        if len(containers)==1 and containers[0]['Image']==image:
            result['cleanup']=cleanup(docker,name,owner,mounts)
        else:result['cleanup']={'removed':False,'failure':'所有imageを確認できない'}
    except Exception as error:result['cleanup']={'removed':False,'failure':str(error)}
    if not result['cleanup']['removed']:result['passed']=False
    (out/'linux-run.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'passed':result['passed'],'report':str(out/'linux-run.json')}))
raise SystemExit(0 if result['passed'] else 1)
