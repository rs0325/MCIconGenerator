#!/usr/bin/env python3
"""Explicit local vanilla integration QA; no assets are added to the repository."""
import argparse
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
parser.add_argument('--cli',type=Path,default=repo/'cli/build/install/mcicon/bin/mcicon')
parser.add_argument('--cache',type=Path,default=repo/'.local/cache')
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--compare',type=Path)
parser.add_argument('--ids',nargs='+',help='Representative IDs instead of all entries')
parser.add_argument('--offline',action='store_true')
parser.add_argument('--overwrite',action='store_true')
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
    for version in versions:
        command=[str(cli),'render','--version',version,'--allow-experimental','--cache',str(args.cache.resolve()),'--out',str(args.out),'--json']
        if args.ids:
            for item in args.ids:command+=['--id',item]
        else:command+=['--all']
        if args.offline:command+=['--offline']
        if args.overwrite:command+=['--overwrite']
        started=time.monotonic()
        with (reports/(version+'-result.json')).open('w') as output,(reports/(version+'-cli.log')).open('w') as log:
            process=subprocess.run(command,stdout=output,stderr=log)
        manifest=args.out/'java'/version/'icons/manifest.json'
        if manifest.exists():
            comparison=args.compare/'java'/version/'icons/manifest.json' if args.compare else None
            try:report=audit_module.audit(manifest,compare=comparison,require_all=not args.ids)
            except Exception as error:report={'version':version,'audited':0,'failures':[str(error)]}
        else:report={'version':version,'audited':0,'failures':['CLI produced no manifest']}
        report.update(exitCode=process.returncode,elapsedSeconds=round(time.monotonic()-started,2))
        (reports/(version+'-audit.json')).write_text(json.dumps(report,indent=2)+'\n')
        if process.returncode or report['failures']:failures.append(version)
        print(version,'PASS' if version not in failures else 'FAIL',report['audited'],'icons',report['elapsedSeconds'],'seconds',flush=True)
(reports/('matrix-'+versions[0]+'-'+versions[-1]+'.json')).write_text(json.dumps({'versions':versions,'failedVersions':failures},indent=2)+'\n')
raise SystemExit(bool(failures))
