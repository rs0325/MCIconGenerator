#!/usr/bin/env python3
"""事前に確定した16px針oracleへ、専用native jobとsampling採取を接続する。"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import zipfile
from qa.needle_process import collect
from qa.needle_world import validate_image

repo=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--out',type=Path,required=True)
p.add_argument('--cache',type=Path,required=True)
p.add_argument('--proofs',type=Path,required=True)
p.add_argument('--prepared',type=Path,required=True)
p.add_argument('--distribution',type=Path,default=repo/'cli/build/install/mcicon')
p.add_argument('--versions',nargs='+')
p.add_argument('--production-worker',type=Path,required=True,help='確定依存commitのimmutable worker')
p.add_argument('--dependency-commit',required=True)
p.add_argument('--docker-context',default='colima-mcicon')
p.add_argument('--docker-image',required=True)
p.add_argument('--stall-diagnostic',type=Path,help='45秒超過時だけ一度呼ぶ任意の私有診断script')
a=p.parse_args()
if not a.docker_image.startswith('sha256:'):p.error('測定済みimageのsha256が必要です')
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cache=a.cache.resolve();dist=out/'distribution';shutil.copytree(a.distribution.resolve(),dist)
worker=dist/'worker/worker-agent.jar';shutil.copyfile(a.production_worker.resolve(),worker);sha=lambda data:hashlib.sha256(data).hexdigest()
core=list((dist/'lib').glob('core-*.jar'))
if len(core)!=1:raise ValueError('core JARが一意ではありません')
classes=out/'classes';classes.mkdir()
source=repo/'scripts/qa/NeedleSamplingAgent.java'
subprocess.run(['javac','--release','8','-cp',str(worker),'-d',str(classes),str(source)],check=True)
qa_worker=out/'sampling-worker.jar'
with zipfile.ZipFile(worker) as original,zipfile.ZipFile(qa_worker,'w',compression=zipfile.ZIP_DEFLATED) as target:
    for entry in original.infolist():
        data=original.read(entry.filename)
        if entry.filename=='META-INF/MANIFEST.MF':data=b'Manifest-Version: 1.0\r\nPremain-Class: io.github.rs0325.mcicon.qa.NeedleSamplingAgent\r\n\r\n'
        target.writestr(entry.filename,data)
    for compiled in classes.rglob('*.class'):target.write(compiled,compiled.relative_to(classes).as_posix())
versions=a.versions or [v['id'] for v in json.loads((repo/'adapters/releases.json').read_text())['versions']]
known={v['id'] for v in json.loads((repo/'adapters/releases.json').read_text())['versions']}
if len(set(versions))!=len(versions) or not set(versions)<=known:raise ValueError('固定版を重複せず指定してください')
inputs={str(f.relative_to(cache)):sha(f.read_bytes()) for f in cache.rglob('*') if f.is_file() and not any(part.startswith('worker-') for part in f.relative_to(cache).parts) and not f.name.endswith('.lock')}
report={'scope':'固定51版のworld/playerなし既定針・16px、測定済みLinux amd64 Mesa image。採取workerは元GUI atlas入口だけを観測し、期待値計算とは分離する。','dependencyCommit':a.dependency_commit,'coreSha256':sha(core[0].read_bytes()),'productionWorkerSha256':sha(worker.read_bytes()),'samplingWorkerSha256':sha(qa_worker.read_bytes()),'imageId':a.docker_image,'sourcesSha256':{str(f.relative_to(repo)):sha(f.read_bytes()) for f in [Path(__file__).resolve(),source,repo/'scripts/qa/needle_process.py',repo/'scripts/qa/needle_world.py',repo/'scripts/verify-needle-inputs.py',repo/'scripts/needle-bytecode.py',repo/'scripts/verify-flat-textures.py',repo/'scripts/qa/FlatTextureProbe.java',repo/'scripts/qa/NeedleTextureProbe.java',repo/'qa/needle-source-contracts.json',repo/'qa/needle-world-lighting.json']},'initialWorkers':sorted(f.name for f in cache.glob('worker-*')),'expectedVersions':versions,'results':[]}
if report['initialWorkers']:raise ValueError('専用cacheに既存workerがあり所有範囲を確認できません')
(out/'input-hashes.json').write_text(json.dumps(inputs,indent=2)+'\n')

def save():
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')

for version in versions:
    proof_path=a.proofs.resolve()/(version+'.json');prepared_path=a.prepared.resolve()/(version+'.json')
    proof=json.loads(proof_path.read_text());prepared=json.loads(prepared_path.read_text())
    if not proof['passed'] or not prepared['passed'] or not prepared['preparedBeforePng'] or prepared['proofSha256']!=sha(proof_path.read_bytes()):raise ValueError('PNG生成前の針根拠が一致しません: '+version)
    validate_image(proof['worldLighting'],a.docker_image)
    result={'version':version,'proofSha256':sha(proof_path.read_bytes()),'preparedSha256':sha(prepared_path.read_bytes()),'startedAfterPreparation':time.time()>=prepared_path.stat().st_mtime,'ids':['minecraft:'+item for item in proof['items']]};report['results'].append(result);save()
    command=[str(dist/'bin/mcicon'),'--debug','render','--version',version,'--size','16','--cache',str(cache),'--offline','--allow-experimental','--out',str(out),'--worker-jar',str(qa_worker),'--docker-context',a.docker_context,'--docker-image',a.docker_image,'--json']
    for item in proof['items']:command.extend(['--id','minecraft:'+item])
    before=set(cache.glob('worker-*'))
    result.update(collect(command,['docker','--context',a.docker_context],cache,qa_worker,a.docker_image,out,version,a.stall_diagnostic.resolve() if a.stall_diagnostic else None))
    try:
        owned=set(cache.glob('worker-*'))-before
        if len(owned)!=1:result.setdefault('failure','所有workerが一意ではありません')
        else:
            job=owned.pop();native=(job/'worker.log').read_text(errors='replace')
            (out/(version+'-worker.log')).write_text(native)
            observations=[json.loads(line.split('[mcicon needle sampling] ',1)[1]) for line in native.splitlines() if '[mcicon needle sampling] ' in line]
            result['samplingObservations']=observations
            result['workerLogSha256']=sha(native.encode())
            if proof['sampling']['runtimeObservationRequired']:
                if Counter(o['id'] for o in observations)!=Counter({item:2 for item in result['ids']}) or any(o['guiScale']!=1 or o['slotTexturePixels']!=16 or o['guiWidth']!=16 or o['guiHeight']!=16 or not o['beforeAtlasDraw'] for o in observations):result.setdefault('failure','生成前の16px sampling観測が不足しています')
            if result['exitCode']==0:
                manifest=out/'java'/version/'icons/manifest.json';m=json.loads(manifest.read_text())
                result['manifestSha256']=sha(manifest.read_bytes());result['catalogSha256']=sha(json.dumps(m['catalog'],sort_keys=True).encode());result['needleCatalog']=[entry for entry in m['catalog'] if entry['id'] in ['minecraft:clock','minecraft:compass','minecraft:recovery_compass']];result['settings']=m['settings'];result['context']=m['context'];result['entryCount']=len(m['entries'])
                result['failedEntries']=[entry for entry in m['entries'] if entry['status']!='success']
                if result['failedEntries']:result.setdefault('failure','針PNGの生成が失敗しています')
                if m['settings']['agentSha256']!=report['samplingWorkerSha256'] or m['settings']['dockerImageId']!=a.docker_image or m['settings']['size']!=16 or m['context']['world']!='absent' or m['context']['player']!='absent' or m['context']['count']!=1 or m['context']['overlays']:result.setdefault('failure','針jobの生成条件が一致しません')
                if set(e['id'] for e in result['needleCatalog'])!=set(result['ids']):result.setdefault('failure','実registry catalogの針母集団が一致しません')
                if len(m['entries'])!=len(result['ids']) or set(e['id'] for e in m['entries'])!=set(result['ids']):result.setdefault('failure','実出力の針母集団が一致しません')
            result['ownedWorker']=job.name
            if (job/'worker-agent.jar').exists() or list(job.glob('icon-*')):result.setdefault('failure','所有worker一時ファイルが残っています')
    except Exception as error:result.setdefault('failure','採取結果: '+str(error))
    result['passed']=result['exitCode']==0 and 'failure' not in result
    if result['passed']:
        try:
            shutil.rmtree(job);result['ownedWorkerRemoved']=not job.exists()
        except Exception as error:result.update(passed=False,failure='所有worker削除: '+str(error))
    save()
    print(version,'PASS' if result['passed'] else 'FAIL',result.get('failure',''),result['elapsedSeconds'],'seconds',flush=True)
report['officialInputsModified']=any(not (cache/path).is_file() or sha((cache/path).read_bytes())!=digest for path,digest in inputs.items())
report['ownedWorkerDirectories']=sorted(f.name for f in cache.glob('worker-*'))
report['passed']=len(report['results'])==len(versions) and all(r['passed'] for r in report['results']) and not report['officialInputsModified'];save()
raise SystemExit(not report['passed'])
