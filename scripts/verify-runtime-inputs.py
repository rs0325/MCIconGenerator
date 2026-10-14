#!/usr/bin/env python3
"""検証済みcacheから既存インストールfixtureを作り、CLIの入力・Java選択を検証する。"""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time

REPO=Path(__file__).resolve().parents[1]
VERSIONS=('1.13','1.21.4','26.3')
SPEC=importlib.util.spec_from_file_location('audit_export',REPO/'scripts/audit-export.py')
AUDIT=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(AUDIT)


def digest(path,algorithm='sha256'):
    with path.open('rb') as stream:return hashlib.file_digest(stream,algorithm).hexdigest()


def fresh(path):
    path=path.resolve()
    if path.exists():raise ValueError('新しい作業ディレクトリを指定してください')
    path.mkdir(parents=True)
    return path


def safe(root,relative):
    if '\\' in relative or Path(relative).is_absolute() or any(p in ('.','..') for p in relative.split('/')):
        raise ValueError('入力パスが公式subtreeを逸脱しています')
    target=root.joinpath(relative)
    for path in (target,*target.parents):
        if path.is_symlink():raise ValueError('入力symlinkは使用できません')
        if path==root:break
    return target


def allowed(library):
    result=not library.get('rules',[])
    for rule in library.get('rules',[]):
        system=rule.get('os',{})
        if 'name' in system and system['name']!='linux':continue
        if 'arch' in system and not re.fullmatch(system['arch'],'amd64'):continue
        if 'features' in rule:continue
        result=rule['action']=='allow'
    return result


def prepare(args):
    sources=[p.resolve() for p in args.source_cache]
    if any(overlap(args.out.resolve(),p) for p in sources):raise ValueError('出力と検証元cacheは重ねられません')
    root=fresh(args.out);installation=root/'installation';proof={};installed={}
    def source(relative):
        for cache in sources:
            path=safe(cache,relative)
            if path.is_file():return path
        raise FileNotFoundError('検証元cacheの入力不足: '+relative)
    def copy(relative,target,sha1=None,size=None):
        path=source(relative)
        if sha1 is not None and digest(path,'sha1')!=sha1:raise ValueError('公式SHA-1不一致: '+relative)
        if size is not None and path.stat().st_size!=size:raise ValueError('公式size不一致: '+relative)
        proof[str(path)]=digest(path);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
        if target.is_relative_to(installation):installed[target.relative_to(installation).as_posix()]=digest(target)
    manifest=source('metadata/version_manifest_v2.json')
    if digest(manifest)!=source('metadata/version_manifest_v2.json.sha256').read_text().strip():raise ValueError('manifest SHA256不一致')
    entries=json.loads(manifest.read_text(encoding='utf-8'))['versions']
    for version in VERSIONS:
        cache=root/('cache-'+version)
        copy('metadata/version_manifest_v2.json',cache/'metadata/version_manifest_v2.json')
        copy('metadata/version_manifest_v2.json.sha256',cache/'metadata/version_manifest_v2.json.sha256')
        entry=next(e for e in entries if e['id']==version)
        copy('versions/'+version+'/version.json',installation/'versions'/version/(version+'.json'),entry['sha1'])
        metadata=json.loads((installation/'versions'/version/(version+'.json')).read_text(encoding='utf-8'))
        client=metadata['downloads']['client'];copy('versions/'+version+'/client.jar',installation/'versions'/version/(version+'.jar'),client['sha1'],client['size'])
        if 'client_mappings' in metadata['downloads']:
            mapping=metadata['downloads']['client_mappings'];relative='versions/'+version+'/mappings.txt';copy(relative,cache/relative,mapping['sha1'],mapping.get('size'))
        elif version.startswith(('1.13','1.14')):
            pins=json.loads((REPO/'adapters/legacy-mappings.json').read_text(encoding='utf-8'))[version]
            for name in ('feather','intermediary'):
                relative='mappings/'+version+'/'+name+'.jar';copy(relative,cache/relative,pins[name])
        for library in metadata['libraries']:
            if not allowed(library):continue
            downloads=library.get('downloads',{});references=[]
            if 'artifact' in downloads:references.append(downloads['artifact'])
            if 'linux' in library.get('natives',{}):references.append(downloads['classifiers'][library['natives']['linux'].replace('${arch}','64')])
            for reference in references:
                relative='libraries/'+reference['path'];copy(relative,safe(installation,relative),reference['sha1'],reference.get('size'))
        index=metadata['assetIndex'];relative='assets/indexes/'+index['id']+'.json';copy(relative,safe(installation,relative),index['sha1'],index.get('size'))
        objects=json.loads((installation/relative).read_text(encoding='utf-8'))['objects']
        for name,reference in objects.items():
            if name.endswith('.ogg'):continue
            value=reference['hash']
            if not re.fullmatch('[0-9a-f]{40}',value):raise ValueError('asset hash不正')
            relative='assets/objects/'+value[:2]+'/'+value;copy(relative,installation/relative,value,reference['size'])
    (root/'source-proof.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    (root/'installation-proof.json').write_text(json.dumps(installed,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'sourceFiles':len(proof),'installationFiles':len(installed)}))


def overlap(left,right):
    return left.is_relative_to(right) or right.is_relative_to(left)


def audit_case(folder,version,raw,fixture,worker):
    mode='raw' if raw else 'icons';manifest=folder/'out/java'/version/mode/'manifest.json'
    if list((folder/'out').rglob('manifest.json'))!=[manifest]:raise ValueError('manifestは期待位置の1件が必要です')
    data=json.loads(manifest.read_text(encoding='utf-8'));identifier='minecraft:block/stone' if raw else 'minecraft:stone'
    entries=data['entries']
    if data['version']!=version or data['mode']!=mode or len(entries)!=1 or entries[0]['id']!=identifier or entries[0]['status']!='success' or entries[0]['kind']!='block' or entries[0]['path']!='assets/minecraft/textures/block/stone.png':raise ValueError('期待stone entryの集合が不一致です')
    if len(list((folder/'out').rglob('*.png')))!=1:raise ValueError('stone PNGは1枚が必要です')
    if data['sourceSha256']!=digest(fixture/'installation/versions'/version/(version+'.jar')):raise ValueError('元JAR hashが不一致です')
    if not raw and (data['settings'].get('agentSha256')!=digest(worker) or data['settings'].get('size')!=64):raise ValueError('worker hashまたはsizeが不一致です')
    result=AUDIT.audit(manifest,source_jar=fixture/'installation/versions'/version/(version+'.jar') if raw else None)
    if result['failures'] or result['audited']!=1:raise ValueError('出力監査失敗: '+str(result['failures']))
    return data,result


def mount_path(value,windows=None):
    windows=os.name=='nt' if windows is None else windows
    value=value.replace('\\','/').rstrip('/')
    if windows:
        for prefix in ('/run/desktop/mnt/host/','/host_mnt/','/mnt/'):
            if value.startswith(prefix) and re.match('[A-Za-z]/',value[len(prefix):]):
                value=value[len(prefix)]+':/'+value[len(prefix)+2:];break
        return value.casefold()
    return value


def stop_owned(process,cache,context):
    errors=[];removed=[]
    if process.poll() is None:
        if os.name=='nt':
            try:subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True,timeout=10)
            except subprocess.TimeoutExpired:errors.append('taskkill期限超過')
        else:process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:errors.append('CLI終了期限超過')
    directories=[p.name for p in cache.glob('worker-*') if p.is_dir()]
    try:
        prefix=['docker','--context',context]
        names=subprocess.run(prefix+['ps','-a','--filter','name=mcicon-worker-','--format','{{.Names}}'],capture_output=True,text=True,check=True,timeout=10).stdout.splitlines()
        for name in names:
            if not name.startswith('mcicon-worker-'):continue
            info=json.loads(subprocess.run(prefix+['inspect',name],capture_output=True,text=True,check=True,timeout=10).stdout)[0]
            arguments=info['Config']['Cmd'] or []
            mounts=info['Mounts']
            if not any(m.get('Destination')=='/mcicon/cache' and mount_path(m['Source']) in {mount_path(str(cache)),mount_path(str(cache.resolve()))} for m in mounts):continue
            if not any('-javaagent:/mcicon/worker.jar=/mcicon/cache/'+directory+'/agent.json' in arguments for directory in directories):continue
            subprocess.run(prefix+['rm','-f',name],capture_output=True,check=True,timeout=10);removed.append(name)
    except (subprocess.SubprocessError,ValueError,KeyError) as failure:errors.append(type(failure).__name__)
    if not errors:
        for directory in directories:(cache/directory/'worker-agent.jar').unlink(missing_ok=True)
    return {'removedContainers':removed,'errors':errors,'workerSnapshotsAbsent':not list(cache.rglob('worker-agent.jar'))}


def execute(command,out,err,cache,context,timeout=240):
    process=subprocess.Popen(command,stdout=out,stderr=err)
    try:return process.wait(timeout=timeout),None
    except subprocess.TimeoutExpired:
        return 124,stop_owned(process,cache,context)
    except BaseException:
        stop_owned(process,cache,context);raise


def verify(args):
    fixture=args.fixture.resolve();distribution=args.distribution.resolve()
    if overlap(args.out.resolve(),fixture) or overlap(args.out.resolve(),distribution):raise ValueError('検証出力と入力は重ねられません')
    root=fresh(args.out);args.created_output=root
    launcher=distribution/'bin'/('mcicon.bat' if os.name=='nt' else 'mcicon')
    worker=args.worker.resolve() if args.worker else distribution/'worker/worker-agent.jar'
    before=json.loads((fixture/'installation-proof.json').read_text(encoding='utf-8'))
    def unchanged():return all(digest(safe(fixture/'installation',p))==h for p,h in before.items())
    if not unchanged():raise ValueError('開始前のインストールfixtureが変わっています')
    progress=[];progress_lock=threading.Lock()
    def run(label,version,home=None,raw=False):
        folder=root/label;folder.mkdir();cache=folder/'cache'
        shutil.copytree(fixture/('cache-'+version),cache,ignore=shutil.ignore_patterns('worker-*','*.part','*.lock'))
        command=[str(launcher),'--debug','extract' if raw else 'render','--version',version,'--offline','--cache',str(cache),
            '--minecraft-dir',str(fixture/'installation'),'--out',str(folder/'out'),'--json']
        if raw:command+=['--texture','block/stone']
        else:
            command+=['--id','stone','--allow-experimental','--worker-jar',str(worker),'--docker-context',args.context,'--docker-image',args.image]
            if home is not None:command+=['--java-home',home]
        start=time.monotonic()
        with (folder/'stdout.json').open('wb') as out,(folder/'stderr.log').open('wb') as err:
            exit_code,cleanup=execute(command,out,err,cache,args.context)
        result={'label':label,'version':version,'exitCode':exit_code,'elapsedSeconds':time.monotonic()-start}
        if cleanup is not None:result.update(supervisorTimedOut=True,cleanup=cleanup)
        manifests=list((folder/'out').rglob('manifest.json'))
        if manifests:
            try:
                manifest,audit=audit_case(folder,version,raw,fixture,worker);result.update(complete=manifest['complete'],settings=manifest['settings'],javaMajor=manifest.get('javaMajor'),audit=audit,auditPassed=True,
                    manifestSha256=digest(manifests[0]),pngSha256={p.relative_to(folder/'out').as_posix():digest(p) for p in (folder/'out').rglob('*.png')})
            except (ValueError,KeyError,OSError) as failure:result.update(auditPassed=False,auditFailure=str(failure))
        (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        with progress_lock:
            progress.append(result);(root/'summary.json').write_text(json.dumps({'status':'RUNNING','records':progress},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        return result
    jobs=[('default-java8','1.13',None),('default-java25','26.3',None),('default-java21','1.21.4',None),('explicit-java21','1.21.4','/opt/java/java21')]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:records=list(pool.map(lambda job:run(*job),jobs))
    for record,major in zip(records,(8,25,21,21)):
        if record['exitCode']!=0 or not record.get('complete') or not record.get('auditPassed') or record.get('javaMajor')!=major:raise ValueError('正常描画の検証失敗: '+record['label'])
    if records[2]['settings']!=records[3]['settings'] or records[2]['pngSha256']!=records[3]['pngSha256']:raise ValueError('既定と明示Java21が不一致')
    for label,home,code,exit_code in [('old-java17','/opt/java/java17','WORKER_JAVA_INCOMPATIBLE',2),('missing-java','/not-installed/java','WORKER_JAVA_UNAVAILABLE',1)]:
        record=run(label,'1.21.4',home);records.append(record)
        if record['exitCode']!=exit_code or code not in (root/label/'stderr.log').read_text(encoding='utf-8') or record.get('pngSha256'):raise ValueError('Java拒否条件の検証失敗')
    installation=fixture/'installation'
    for label,cache in [('cache-same-input',installation),('cache-inside-input',installation/'overlap-cache'),('cache-contains-input',fixture)]:
        folder=root/label;folder.mkdir()
        command=[str(launcher),'cache','status','--cache',str(cache),'--minecraft-dir',str(installation),'--json']
        with (folder/'stdout.json').open('wb') as out,(folder/'stderr.log').open('wb') as err:
            exit_code,cleanup=execute(command,out,err,folder,args.context,timeout=30)
        record={'label':label,'exitCode':exit_code,'expectedCode':'INVALID_REQUEST'}
        (folder/'result.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8');records.append(record)
        if exit_code!=2 or 'INVALID_REQUEST' not in (folder/'stderr.log').read_text(encoding='utf-8') or (folder/'stdout.json').stat().st_size:raise ValueError('cache重複の拒否条件が不一致です')
    if (installation/'overlap-cache').exists():raise ValueError('拒否したcacheが入力内へ作成されました')
    record=run('raw-import','1.21.4',raw=True);records.append(record)
    if record['exitCode']!=0 or not record.get('complete') or not record.get('auditPassed'):raise ValueError('raw検証失敗')
    if not unchanged() or list(root.rglob('*.part')) or list(root.rglob('worker-agent.jar')):raise ValueError('入力変更またはpartial/snapshot残存')
    summary={'status':'PASS','records':records,'workerSha256':digest(worker),'coreSha256':digest(next((distribution/'lib').glob('core-*.jar'))),
        'installationFilesUnchanged':len(before),'defaultExplicitIdentical':True,'partialsAbsent':True,'workerSnapshotsAbsent':True}
    (root/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(summary,ensure_ascii=False))


def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='command',required=True)
    prepare_parser=commands.add_parser('prepare');prepare_parser.add_argument('--source-cache',type=Path,action='append',required=True);prepare_parser.add_argument('--out',type=Path,required=True)
    verify_parser=commands.add_parser('verify');verify_parser.add_argument('--fixture',type=Path,required=True);verify_parser.add_argument('--distribution',type=Path,required=True)
    verify_parser.add_argument('--worker',type=Path);verify_parser.add_argument('--context',required=True);verify_parser.add_argument('--image',required=True);verify_parser.add_argument('--out',type=Path,required=True)
    verify_parser.add_argument('--jobs',type=int,choices=(1,2),default=1)
    args=parser.parse_args()
    try:prepare(args) if args.command=='prepare' else verify(args)
    except BaseException as failure:
        report=args.out/'summary.json'
        if args.command=='verify' and getattr(args,'created_output',None) is not None and report.is_file():
            data=json.loads(report.read_text(encoding='utf-8'));data.update(status='FAIL',failure=type(failure).__name__+': '+str(failure));report.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        raise


if __name__=='__main__':main()
