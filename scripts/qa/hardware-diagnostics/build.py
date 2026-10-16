#!/usr/bin/env python3
"""worker同梱のshaded ASMから外部process拒否QAを作り、ホストJDKで正controlを確認する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    worker=args.worker.resolve();source=Path(__file__).resolve().parent
    java=Path(os.environ['JAVA_HOME'])/'bin';classes=out/'classes'
    sources=[source/'SpawnGuard.java',source/'GuardCheck.java']
    subprocess.run([str(java/'javac'),'--release','8','-encoding','UTF-8','-cp',str(worker),'-d',str(classes),*map(str,sources)],check=True,timeout=60)
    jar=out/'spawn-guard.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
        target.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: io.github.rs0325.mcicon.qa.SpawnGuard\nCan-Retransform-Classes: true\n\n')
        for path in sorted(classes.rglob('*.class')):target.write(path,path.relative_to(classes).as_posix())
        with zipfile.ZipFile(worker) as dependency:
            for name in dependency.namelist():
                if name.startswith('io/github/rs0325/mcicon/worker/shaded/asm/') and name.endswith('.class'):
                    target.writestr(name,dependency.read(name))
    check=subprocess.run([str(java/'java'),'-javaagent:'+str(jar),'-cp',str(classes),'GuardCheck'],capture_output=True,timeout=20)
    (out/'check.stdout').write_bytes(check.stdout);(out/'check.stderr').write_bytes(check.stderr)
    passed=check.returncode!=0 and b'MCICON_QA_EXTERNAL_PROCESS_REQUEST' in check.stderr and b'external process guard installed' in check.stderr
    result={'passed':passed,'exitCode':check.returncode,'calibrationScope':'ホストJDKだけ。実行先の各JavaでもGuardCheckの拒否を確認してから要求0を判定する。',
            'sourcesSha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,source/'NativeProbe.java',Path(__file__),worker,jar]}}
    (out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    if not passed:raise SystemExit(1)
    print('外部process拒否QAの正control PASS')


if __name__=='__main__':main()
