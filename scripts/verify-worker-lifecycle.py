#!/usr/bin/env python3
"""ローカルDockerでPNGとworkerの正常終了を確認する。ゲーム資産は公開しない。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import time

repo=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py')
audit=importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
parser=argparse.ArgumentParser()
parser.add_argument('--versions',nargs='+',default=['1.13','1.13.2','1.21.4','26.3'])
parser.add_argument('--sizes',nargs='+',type=int,default=[16,64,512])
parser.add_argument('--ids',nargs='+',default=['diamond','stone','chest','shield','glass','white_stained_glass'])
parser.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
parser.add_argument('--worker-jar',type=Path,default=repo/'worker/build/libs/worker-0.1.0-SNAPSHOT-agent.jar')
parser.add_argument('--cache',type=Path,required=True,help='この試験専用の準備済みcache')
parser.add_argument('--out',type=Path,required=True,help='存在しない出力ディレクトリ')
parser.add_argument('--compare',type=Path,help='同じサイズの既存PNGの基準出力')
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
expected_ids={item if ':' in item else 'minecraft:'+item for item in args.ids}
out=args.out.resolve();out.mkdir(parents=True)
cache=args.cache.resolve()
distribution=out/'cli'
shutil.copytree(args.cli.resolve().parent.parent,distribution)
worker=out/'worker.jar';shutil.copyfile(args.worker_jar,worker)
docker=['docker','--context',args.docker_context]
report={'scope':'Linux amd64 Mesa Dockerの描画・透過・正常終了。World画質受入ではない。',
        'workerSha256':hashlib.sha256(worker.read_bytes()).hexdigest(),'jobs':[],'passed':False}
for version in args.versions:
    for size in args.sizes:
        folder=out/(version+'-'+str(size));folder.mkdir()
        command=[str(distribution/'bin/mcicon'),'render','--version',version,'--size',str(size),
                 '--allow-experimental','--offline','--cache',str(cache),'--out',str(folder/'output'),
                 '--worker-jar',str(worker),'--docker-context',args.docker_context,'--docker-image',args.docker_image,'--json']
        for item in args.ids:command+=['--id',item]
        owned=set();started=time.monotonic();timed_out=False
        with (folder/'events.jsonl').open('wb') as event_log,(folder/'stdout.json').open('wb') as stdout,(folder/'stderr.log').open('wb') as stderr:
            events=subprocess.Popen([*docker,'events','--filter','type=container','--format','{{json .}}'],stdout=event_log,stderr=subprocess.DEVNULL)
            try:
                process=subprocess.Popen(command,stdout=stdout,stderr=stderr)
                while process.poll() is None:
                    ids=subprocess.run([*docker,'ps','--filter','name=mcicon-worker-','--format','{{.ID}}'],capture_output=True,text=True,check=True).stdout.split()
                    if ids:
                        result=subprocess.run([*docker,'inspect',*ids],capture_output=True,text=True)
                        # --rmの正常終了とinspectが重なった場合、次の観測へ進む。
                        if result.returncode==0:
                            for container in json.loads(result.stdout):
                                if any(m.get('Source')==str(cache) for m in container['Mounts']):owned.add(container['Id'])
                    if time.monotonic()-started>180:
                        timed_out=True;process.terminate()
                        try:process.wait(timeout=20)
                        except subprocess.TimeoutExpired:process.kill();process.wait()
                        break
                    time.sleep(0.5)
                time.sleep(0.5)
            finally:
                events.terminate();events.wait()
        observed=[json.loads(line) for line in (folder/'events.jsonl').read_text().splitlines() if line]
        relevant=[e for e in observed if e['Actor']['ID'] in owned]
        exits=[e['Actor']['Attributes'].get('exitCode') for e in relevant if e['Action']=='die']
        forced=[e['Action'] for e in relevant if e['Action']=='oom' or (e['Action']=='kill' and e['Actor']['Attributes'].get('signal')=='9')]
        job={'version':version,'size':size,'exitCode':process.returncode,'timedOut':timed_out,
             'ownedWorkers':len(owned),'workerExitCodes':exits,'forcedTerminationEvents':forced,
             'elapsedSeconds':round(time.monotonic()-started,3),'images':[],'passed':False}
        if process.returncode==0:
            manifest=folder/'output/java'/version/'icons/manifest.json'
            comparison=args.compare/'java'/version/'icons/manifest.json' if args.compare else None
            job['audit']=audit.audit(manifest,compare=comparison)
            metadata=json.loads(manifest.read_text())
            for key in ['renderer','context','settings']:job[key]=metadata[key]
            for entry in metadata['entries']:
                width,height,pixels=audit.rgba_png((manifest.parent/entry['path']).read_bytes())
                alpha=pixels[3::4]
                job['images'].append({'id':entry['id'],'width':width,'height':height,
                    'backgroundAlphaZeroPixels':alpha.count(0),'fractionalAlphaPixels':sum(0<a<255 for a in alpha)})
            job['passed']=bool(not timed_out and not job['audit']['failures'] and owned and len(exits)==len(owned)
                and all(code=='0' for code in exits) and not forced
                and len(job['images'])==len(args.ids)
                and {i['id'] for i in job['images']}==expected_ids
                and all(i['width']==i['height']==size and i['backgroundAlphaZeroPixels']>0 for i in job['images'])
                and all(i['fractionalAlphaPixels']>0 for i in job['images'] if i['id']=='minecraft:white_stained_glass'))
        report['jobs'].append(job)
        report['passed']=all(j['passed'] for j in report['jobs']) and len(report['jobs'])==len(args.versions)*len(args.sizes)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(version,size,'PASS' if job['passed'] else 'FAIL','worker exits',exits,flush=True)
        if not job['passed']:raise SystemExit(1)
