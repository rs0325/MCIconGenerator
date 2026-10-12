#!/usr/bin/env python3
"""Explicit local vanilla integration QA; no assets are added to the repository."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

repo=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('audit_export',repo/'scripts/audit-export.py')
audit_module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)
parser=argparse.ArgumentParser()
parser.add_argument('--versions',nargs='+')
parser.add_argument('--mode',choices=('icons','raw'),default='icons')
parser.add_argument('--size',type=int,choices=(16,32,64,128,256,512),default=64)
parser.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
parser.add_argument('--cache',type=Path,default=repo/'.local/cache')
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--compare',type=Path)
parser.add_argument('--ids',nargs='+',help='Item IDs (icons) or texture IDs (raw) instead of all entries')
parser.add_argument('--offline',action='store_true')
parser.add_argument('--overwrite',action='store_true')
parser.add_argument('--docker-context',default='colima-mcicon')
parser.add_argument('--docker-image',default='mcicon-render:dev')
args=parser.parse_args()
versions=args.versions or [v['id'] for v in json.loads((repo/'adapters/releases.json').read_text())['versions']]
known={v["id"] for v in json.loads((repo/"adapters/releases.json").read_text())["versions"]}
if len(versions)!=len(set(versions)) or any(version not in known for version in versions):
    parser.error("Use unique exact versions from adapters/releases.json")
args.out=args.out.resolve();args.out.mkdir(parents=True,exist_ok=True)
reports=args.out/'reports';reports.mkdir(exist_ok=True)
failures=[]
# Builds performed during QA cannot replace libraries that a running JVM needs.
with tempfile.TemporaryDirectory(prefix='.cli-snapshot-',dir=args.out) as temporary:
    distribution=Path(temporary)/'mcicon'
    shutil.copytree(args.cli.resolve().parent.parent,distribution)
    cli=distribution/'bin/mcicon'
    core_jars=list((distribution/'lib').glob('core-*.jar'))
    if len(core_jars)!=1:raise ValueError('CLI distribution must contain one core JAR')
    core_hash=hashlib.sha256(core_jars[0].read_bytes()).hexdigest()
    for version in versions:
        command=[str(cli),'render' if args.mode=='icons' else 'extract','--version',version,'--cache',str(args.cache.resolve()),'--out',str(args.out),'--json']
        if args.mode=='icons':command+=['--allow-experimental','--size',str(args.size),'--docker-context',args.docker_context,'--docker-image',args.docker_image]
        if args.ids:
            for item in args.ids:command+=['--id' if args.mode=='icons' else '--texture',item]
        else:command+=['--all']
        if args.offline:command+=['--offline']
        if args.overwrite:command+=['--overwrite']
        started=time.monotonic()
        with (reports/(version+'-result.json')).open('w') as output,(reports/(version+'-cli.log')).open('w') as log:
            process=subprocess.run(command,stdout=output,stderr=log)
        manifest=args.out/'java'/version/args.mode/'manifest.json'
        if manifest.exists():
            comparison=args.compare/'java'/version/args.mode/'manifest.json' if args.compare else None
            source=args.cache.resolve()/'versions'/version/'client.jar' if args.mode=='raw' else None
            try:report=audit_module.audit(manifest,source_jar=source,compare=comparison,require_all=args.mode=='icons' and not args.ids)
            except Exception as error:report={'version':version,'audited':0,'failures':[str(error)]}
        else:report={'version':version,'audited':0,'failures':['CLI produced no manifest']}
        report.update(exitCode=process.returncode,elapsedSeconds=round(time.monotonic()-started,2),cliCoreSha256=core_hash)
        (reports/(version+'-audit.json')).write_text(json.dumps(report,indent=2)+'\n')
        if process.returncode or report['failures']:failures.append(version)
        print(version,'PASS' if version not in failures else 'FAIL',report['audited'],args.mode,report['elapsedSeconds'],'seconds',flush=True)
(reports/('matrix-'+versions[0]+'-'+versions[-1]+'.json')).write_text(json.dumps({'versions':versions,'failedVersions':failures},indent=2)+'\n')
raise SystemExit(bool(failures))
