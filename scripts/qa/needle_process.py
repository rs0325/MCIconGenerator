"""16px針jobの有限監督と、専用cacheから起動したworkerの所有確認。"""
import hashlib
import json
from pathlib import Path
import subprocess
import time


def stop(process):
    if process is None or process.poll() is not None:return {'alreadyExited':True}
    result={'terminated':False,'killed':False}
    try:process.terminate();result['terminated']=True
    except Exception as error:result['terminateError']=str(error)
    try:process.wait(timeout=10)
    except (subprocess.TimeoutExpired,OSError):
        process.kill();result['killed']=True;process.wait(timeout=5)
    return result


def owned(container,cache,worker_hash,image,directories,verified):
    mounts={m['Destination']:m for m in container['Mounts']}
    cache_mount=mounts.get('/mcicon/cache');snapshot=mounts.get('/mcicon/worker.jar')
    if not cache_mount or not snapshot:return False
    path=Path(snapshot['Source'])
    if cache_mount['Source']!=str(cache) or not cache_mount['RW'] or snapshot['RW'] or path.parent not in directories or path.name!='worker-agent.jar':return False
    arguments=container['Config'].get('Cmd') or []
    agent='-javaagent:/mcicon/worker.jar=/mcicon/cache/'+path.parent.name+'/agent.json'
    if agent not in arguments or container['Image']!=image or not container['Name'].startswith('/mcicon-worker-'):return False
    if container['Id'] in verified:return True
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==worker_hash


def collect(command,docker,cache,worker,image,folder,version,diagnostic=None,run=subprocess.run,popen=subprocess.Popen):
    before=set(cache.glob('worker-*'));worker_hash=hashlib.sha256(worker.read_bytes()).hexdigest()
    started=time.monotonic();process=None;events=None;verified=set();result={'cleanupErrors':[]}
    def discover(cleanup=False):
        timeout=10 if cleanup else max(0.001,min(10,240-(time.monotonic()-started)))
        ids=run([*docker,'ps','-a','--filter','name=mcicon-worker-','--format','{{.ID}}'],capture_output=True,text=True,check=True,timeout=timeout).stdout.split()
        directories=set(cache.glob('worker-*'))-before;found=[]
        if ids:
            timeout=10 if cleanup else max(0.001,min(10,240-(time.monotonic()-started)))
            response=run([*docker,'inspect',*ids],capture_output=True,text=True,timeout=timeout)
            if response.returncode==0:
                for container in json.loads(response.stdout):
                    if owned(container,cache,worker_hash,image,directories,verified):
                        verified.add(container['Id']);found.append(container)
        return found
    with (folder/(version+'-events.jsonl')).open('wb') as event_log,(folder/(version+'-result.json')).open('w') as stdout,(folder/(version+'-cli.log')).open('w') as stderr:
        try:
            events=popen([*docker,'events','--filter','type=container','--format','{{json .}}'],stdout=event_log,stderr=subprocess.DEVNULL)
            process=popen(command,stdout=stdout,stderr=stderr)
            diagnosed=False
            while process.poll() is None:
                if time.monotonic()-started>=240:
                    result['supervisorTimedOut']=True;raise TimeoutError('針jobの全体期限240秒を超過しました')
                discover()
                try:process.wait(timeout=max(0.001,min(1,240-(time.monotonic()-started))))
                except subprocess.TimeoutExpired:pass
                if process.poll() is None and diagnostic and not diagnosed and time.monotonic()-started>=45:
                    diagnosed=True;digest=None
                    try:
                        digest=hashlib.sha256(diagnostic.read_bytes()).hexdigest()
                        response=run(['python3',str(diagnostic),'--host-pid',str(process.pid),'--version',version],capture_output=True,text=True,timeout=max(0.001,min(60,240-(time.monotonic()-started))))
                        result['stallDiagnostic']={'exitCode':response.returncode,'scriptSha256':digest}
                        (folder/(version+'-diagnostic.log')).write_text(response.stdout+response.stderr)
                    except Exception as error:result['stallDiagnostic']={'failure':str(error),'scriptSha256':digest}
        except Exception as error:result['failure']=type(error).__name__+': '+str(error)
        finally:
            try:result['cliCleanup']=stop(process)
            except Exception as error:result['cleanupErrors'].append('CLI: '+str(error))
            identities=set(verified)
            try:identities.update(container['Id'] for container in discover(cleanup=True))
            except Exception as error:result['cleanupErrors'].append('所有container再検出: '+str(error))
            directories=set(cache.glob('worker-*'))-before
            for identity in identities:
                try:
                    response=run([*docker,'inspect',identity],capture_output=True,text=True,timeout=10)
                    if response.returncode!=0:continue
                    containers=json.loads(response.stdout)
                    if len(containers)!=1 or not owned(containers[0],cache,worker_hash,image,directories,verified):
                        result['cleanupErrors'].append('所有条件が変わりました: '+identity);continue
                    result.setdefault('failure','CLI終了後に所有containerが残存しました')
                    run([*docker,'rm','-f',identity],capture_output=True,text=True,check=True,timeout=10)
                    result.setdefault('removedOwnedContainers',[]).append(identity)
                except Exception as error:result['cleanupErrors'].append('所有container: '+str(error))
            try:result['eventsCleanup']=stop(events)
            except Exception as error:result['cleanupErrors'].append('events: '+str(error))
    result.update(exitCode=process.returncode if process else None,elapsedSeconds=round(time.monotonic()-started,2),ownedContainers=sorted(verified))
    try:
        observed=[json.loads(line) for line in (folder/(version+'-events.jsonl')).read_text().splitlines() if line]
        relevant=[e for e in observed if e['Actor']['ID'] in verified]
        result['workerExitCodes']=[e['Actor']['Attributes'].get('exitCode') for e in relevant if e['Action']=='die']
        result['forcedTerminationEvents']=[e['Action'] for e in relevant if e['Action']=='oom' or e['Action']=='kill' and e['Actor']['Attributes'].get('signal')=='9']
        remaining=run([*docker,'ps','-a','--no-trunc','--format','{{.ID}}'],capture_output=True,text=True,check=True,timeout=10).stdout.split()
        result['remainingOwnedContainers']=sorted(verified.intersection(remaining))
        if len(verified)!=1 or result['workerExitCodes']!=['0'] or result['forcedTerminationEvents'] or result['remainingOwnedContainers'] or result['cleanupErrors']:result.setdefault('failure','所有コンテナの正常終了を確認できません')
    except Exception as error:result.setdefault('failure','終了記録: '+str(error))
    return result
