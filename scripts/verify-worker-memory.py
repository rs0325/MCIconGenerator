#!/usr/bin/env python3
"""専用cacheでゲームworkerのメモリ指定・画像一致・失敗時回収を順次検証する。"""
import argparse
from datetime import datetime,timezone
import importlib.util
import json
from pathlib import Path
import shutil
import re
import subprocess
import threading
import time

REPO=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('runtime_inputs',REPO/'scripts/verify-runtime-inputs.py')
RUNTIME=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(RUNTIME)


def prepare(source,cache):
    proof={}
    def copy(relative,sha1=None,size=None):
        original=RUNTIME.safe(source,relative);target=RUNTIME.safe(cache,relative)
        if sha1 is not None and RUNTIME.digest(original,'sha1')!=sha1:raise ValueError('公式SHA-1不一致: '+relative)
        if size is not None and original.stat().st_size!=size:raise ValueError('公式size不一致: '+relative)
        proof[relative]=RUNTIME.digest(original)
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(original,target)
        recorded=source/(relative+'.sha256')
        if recorded.exists():
            if recorded.read_text().strip()!=proof[relative]:raise ValueError('cache SHA-256不一致: '+relative)
            shutil.copyfile(recorded,cache/(relative+'.sha256'))
    copy('metadata/version_manifest_v2.json')
    if not (cache/'metadata/version_manifest_v2.json.sha256').exists():raise ValueError('manifest SHA-256が必要です')
    entries=json.loads((cache/'metadata/version_manifest_v2.json').read_text())['versions']
    copy('versions/1.21.4/version.json',next(e['sha1'] for e in entries if e['id']=='1.21.4'))
    meta=json.loads((cache/'versions/1.21.4/version.json').read_text())
    for name,path in [('client','client.jar'),('client_mappings','mappings.txt')]:
        ref=meta['downloads'][name];copy('versions/1.21.4/'+path,ref['sha1'],ref.get('size'))
    for library in meta['libraries']:
        if not RUNTIME.allowed(library):continue
        downloads=library.get('downloads',{});refs=[]
        if 'artifact' in downloads:refs.append(downloads['artifact'])
        if 'linux' in library.get('natives',{}):refs.append(downloads['classifiers'][library['natives']['linux'].replace('${arch}','64')])
        for ref in refs:copy('libraries/'+ref['path'],ref['sha1'],ref.get('size'))
    ref=meta['assetIndex'];relative='assets/indexes/'+ref['id']+'.json';copy(relative,ref['sha1'],ref.get('size'))
    for name,ref in json.loads((cache/relative).read_text())['objects'].items():
        if name.endswith('.ogg'):continue
        value=ref['hash'];copy('assets/objects/'+value[:2]+'/'+value,value,ref['size'])
    return proof


def audit_success(manifest,worker,image,source,compare=None):
    value=json.loads(manifest.read_text())
    entries=value['entries']
    if (value['version']!='1.21.4' or value['mode']!='icons' or not value['complete']
            or len(entries)!=1 or entries[0]['id']!='minecraft:stone' or entries[0]['status']!='success'
            or entries[0]['path']!='assets/minecraft/textures/block/stone.png'
            or value['counts']['succeeded']!=1 or value['counts']['failed']!=0
            or value['settings']['size']!=64 or value['sourceSha256']!=source
            or value['settings']['agentSha256']!=worker or value['settings']['dockerImageId']!=image
            or value['environment']['imageId']!=image
            or len(list(manifest.parent.rglob('*.png')))!=1):raise ValueError('stone正常出力の識別・件数不一致')
    result=RUNTIME.AUDIT.audit(manifest,compare=compare)
    if result['failures'] or result['audited']!=1:raise ValueError('stone PNGの監査失敗')
    return result


def heap_evidence(observations,cache):
    directories=set()
    for observation in observations:
        for argument in observation['Config']['Cmd']:
            match=re.fullmatch(r'-javaagent:/mcicon/worker.jar=/mcicon/cache/(worker-[0-9]+)/agent.json',argument)
            if match:directories.add(match[1])
    logs=[cache/directory/'worker.log' for directory in sorted(directories)]
    return {'heapOutOfMemoryEvidence':any('Caused by: java.lang.OutOfMemoryError: Java heap space' in log.read_text(errors='replace') for log in logs),
            'failureLogSha256':[RUNTIME.digest(log) for log in logs]}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-cache',type=Path,required=True);parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--image',required=True);parser.add_argument('--context',default='colima-mcicon')
    parser.add_argument('--only-default',action='store_true',help='最終配布物の既定予算だけを確認する')
    args=parser.parse_args();root=RUNTIME.fresh(args.out);cache=root/'cache';cache.mkdir()
    proof=prepare(args.source_cache.resolve(),cache)
    (root/'input-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
    distribution=REPO/'cli/build/install/mcicon';prefix=['docker','--context',args.context]
    image=subprocess.run(prefix+['image','inspect','--format','{{.Id}}',args.image],capture_output=True,text=True,check=True,timeout=10).stdout.strip()
    data={'status':'RUNNING','version':'1.21.4','size':64,'id':'minecraft:stone','parallelWorkers':1,'imageSha256':image,
          'coreSha256':RUNTIME.digest(distribution/'lib/core-0.1.0-SNAPSHOT.jar'),'workerSha256':RUNTIME.digest(distribution/'worker/worker-agent.jar'),
          'driverSha256':RUNTIME.digest(Path(__file__)),'sourceSha256':{p:RUNTIME.digest(REPO/p) for p in ['core/src/main/java/io/github/rs0325/mcicon/WorkerOptions.java','core/src/main/java/io/github/rs0325/mcicon/internal/WorkerClient.java','cli/src/main/java/io/github/rs0325/mcicon/cli/Main.java']},
          'inputFiles':len(proof),'inputProofSha256':RUNTIME.digest(root/'input-proof.json'),'records':[]}
    report=root/'report.json'
    def save():report.write_text(json.dumps(data,indent=2)+'\n')
    save();baseline=None
    try:
        for label,pair in ([('default',(3072,2048))] if args.only_default else [('default',(3072,2048)),('custom',(4096,2560)),('low',(128,16))]):
            folder=root/label;folder.mkdir();stop=threading.Event();observations=[]
            eventfile=(folder/'docker-events.jsonl').open('wb')
            events=subprocess.Popen(prefix+['events','--filter','type=container','--filter','event=die','--format','{{json .}}'],stdout=eventfile,stderr=subprocess.DEVNULL)
            def observe():
                while not stop.is_set():
                    names=subprocess.run(prefix+['ps','--filter','name=mcicon-worker-','--format','{{.Names}}'],capture_output=True,text=True,timeout=10).stdout.splitlines()
                    for name in names:
                        result=subprocess.run(prefix+['inspect',name],capture_output=True,text=True,timeout=10)
                        if result.returncode:continue
                        info=json.loads(result.stdout)[0]
                        if any(m.get('Destination')=='/mcicon/cache' and RUNTIME.mount_path(m['Source'])==RUNTIME.mount_path(str(cache)) for m in info['Mounts']):observations.append(info)
                    stop.wait(.1)
            monitor=threading.Thread(target=observe);monitor.start()
            command=[str(distribution/'bin/mcicon'),'--debug','render','--version','1.21.4','--id','stone','--size','64','--allow-experimental','--offline','--cache',str(cache),'--out',str(folder/'output'),'--worker-jar',str(distribution/'worker/worker-agent.jar'),'--docker-context',args.context,'--docker-image',image,'--json']
            if label!='default':command+=['--memory-mib',str(pair[0]),'--heap-mib',str(pair[1])]
            (folder/'command.json').write_text(json.dumps(command,indent=2)+'\n')
            started=datetime.now(timezone.utc).isoformat();start=time.monotonic()
            try:
                with (folder/'stdout.json').open('wb') as out,(folder/'stderr.log').open('wb') as err:code,cleanup=RUNTIME.execute(command,out,err,cache,args.context,timeout=240)
            finally:
                stop.set();monitor.join(timeout=25);events.terminate()
                try:events.wait(timeout=10)
                except subprocess.TimeoutExpired:events.kill();events.wait(timeout=5)
                eventfile.close()
            (folder/'container-observations.json').write_text(json.dumps(observations,indent=2)+'\n')
            owned={o['Name'].lstrip('/') for o in observations}
            remaining=subprocess.run(prefix+['ps','-a','--format','{{.Names}}'],capture_output=True,text=True,check=True,timeout=10).stdout.splitlines()
            record={'label':label,'containerMemoryMiB':pair[0],'heapMemoryMiB':pair[1],'exitCode':code,'startedUtc':started,'endedUtc':datetime.now(timezone.utc).isoformat(),'elapsedSeconds':time.monotonic()-start,
                    'observedContainers':len(owned),'ownedContainersAbsent':not set(remaining)&owned,'snapshotsAbsent':not list(cache.glob('worker-*/worker-agent.jar')),'temporaryPngAbsent':not list(cache.glob('worker-*/icon-*.png')),'partFilesAbsent':not list(root.rglob('*.part')),'supervisorCleanup':cleanup}
            data['records'].append(record);save()
            if not owned:raise ValueError('所有containerの観測なし')
            if not all(record[k] for k in ['ownedContainersAbsent','snapshotsAbsent','temporaryPngAbsent','partFilesAbsent']):raise ValueError('所有リソースの回収失敗')
            if any(o['HostConfig']['Memory']!=pair[0]*1024*1024 or '-Xmx'+str(pair[1])+'m' not in o['Config']['Cmd'] for o in observations):raise ValueError('実containerのメモリ指定不一致')
            if label!='low':
                manifest=next((folder/'output').rglob('manifest.json'));result=audit_success(manifest,data['workerSha256'],image,proof['versions/1.21.4/client.jar'],baseline)
                record['audit']=result;record['manifestSha256']=RUNTIME.digest(manifest)
                settings=json.loads(manifest.read_text())['settings'];record['settings']=settings
                record['pngSha256']={p.relative_to(folder/'output').as_posix():RUNTIME.digest(p) for p in (folder/'output').rglob('*.png')}
                if code or result['failures']:raise ValueError('正常描画の受入失敗')
                if baseline is None:baseline=manifest
                elif record['settings']!=data['records'][0]['settings'] or record['pngSha256']!=data['records'][0]['pngSha256']:raise ValueError('予算変更で画像またはsettingsが変化しました')
            else:
                stderr=(folder/'stderr.log').read_text(errors='replace');record['workerTimeout']='WORKER_TIMEOUT' in stderr
                record['errorCode']='WORKER_TIMEOUT' if record['workerTimeout'] else ('WORKER_EXITED' if 'WORKER_EXITED' in stderr else None)
                record.update(heap_evidence(observations,cache))
                record['dockerExitCodes']=[int(e['Actor']['Attributes']['exitCode']) for e in map(json.loads,(folder/'docker-events.jsonl').read_text().splitlines()) if e['Actor']['Attributes'].get('name') in owned and 'exitCode' in e['Actor']['Attributes']]
                if code==0 or code==124 or record['workerTimeout'] or not record['heapOutOfMemoryEvidence']:raise ValueError('低予算失敗の原因・期限を確認できません')
            save()
        data['status']='PASS';save()
    except BaseException as failure:
        data.update(status='FAIL',failure=type(failure).__name__+': '+str(failure));save();raise


if __name__=='__main__':main()
