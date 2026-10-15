#!/usr/bin/env python3
"""既存の境界QAを再検査し、Datafixer gateの変換と解除・期限を検証する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache', type=Path, required=True)
    p.add_argument('--worker', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).resolve().parent
    subprocess.run([sys.executable, str(source.parent / 'startup-stage/build.py'), '--cache', str(a.cache),
                    '--worker', str(a.worker), '--out', str(out / 'stage')], check=True, timeout=120)
    stage = json.loads((out / 'stage/report.json').read_text())
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    asm = a.cache.resolve() / 'libraries/org/ow2/asm/asm/9.6/asm-9.6.jar'
    classes = out / 'classes'
    classes.mkdir()
    sources = [source / 'DatafixerGateAgent.java', source / 'DatafixerGateCheck.java']
    cp = os.pathsep.join(map(str, [out / 'stage/classes', asm, a.worker.resolve()]))
    subprocess.run([str(java / 'javac'), '--release', '21', '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(classes), *map(str, sources)], check=True, timeout=60)
    cp = str(classes) + os.pathsep + cp
    success = out / 'release-test'
    success.mkdir()
    checked = subprocess.run([str(java / 'java'), '-cp', cp, 'mcicon.qa.DatafixerGateCheck', str(success),
                              str(a.cache.resolve() / 'versions/1.21.4/client.jar'),
                              str(a.cache.resolve() / 'versions/1.21.4/mappings.txt')], capture_output=True, text=True, check=True, timeout=30)
    expired = out / 'expiry-test'
    expired.mkdir()
    refused = subprocess.run([str(java / 'java'), '-cp', cp, 'mcicon.qa.DatafixerGateCheck', str(expired)],
                              capture_output=True, timeout=15)
    if refused.returncode != 99 or [line.split('\t')[0] for line in (expired / 'datafixer-gate.tsv').read_text().splitlines()] != ['agent.ready', 'gate.waiting', 'gate.expired']:
        raise AssertionError('gate期限を成功として通過させた')
    late = out / 'late-release-test'
    late.mkdir()
    late_release = subprocess.run([str(java / 'java'), '-cp', cp, 'mcicon.qa.DatafixerGateCheck', str(late), 'late-release'], capture_output=True, timeout=15)
    if late_release.returncode != 99 or [line.split('\t')[0] for line in (late / 'datafixer-gate.tsv').read_text().splitlines()] != ['agent.ready', 'gate.waiting', 'gate.expired']:
        raise AssertionError('期限後の解除を受け入れた')
    jar = out / 'datafixer-gate.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: mcicon.qa.DatafixerGateAgent\n\n')
        for f in sorted(classes.rglob('DatafixerGateAgent*.class')):
            z.write(f, f.relative_to(classes).as_posix())
        with zipfile.ZipFile(asm) as dependency:
            for name in dependency.namelist():
                if name.startswith('org/objectweb/asm/') and name.endswith('.class'):
                    z.writestr(name, dependency.read(name))
    fixture = out / 'unsupported'
    fixture.mkdir()
    (fixture / 'bao.java').write_text('public class bao { public static void main(String[] args) { } }\n')
    subprocess.run([str(java / 'javac'), '-d', str(fixture), str(fixture / 'bao.java')], check=True, timeout=30)
    rejected = subprocess.run([str(java / 'java'), '-javaagent:' + str(jar) + '=' + str(fixture), '-cp', str(fixture), 'bao'], capture_output=True, timeout=15)
    if rejected.returncode != 97 or [line.split('\t')[0] for line in (fixture / 'datafixer-gate.tsv').read_text().splitlines()] != ['agent.ready', 'agent.transform.failed']:
        raise AssertionError('transformer不適合を無計装の成功として通過させた')
    result = {'passed': True, 'check': checked.stdout, 'expiredExitCode': refused.returncode,
              'lateReleaseExitCode': late_release.returncode,
              'unsupportedClassExitCode': rejected.returncode,
              'agentSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'stageAgentSha256': stage['agentSha256'], 'stageBuildReportSha256': hashlib.sha256((out / 'stage/report.json').read_bytes()).hexdigest(),
              'sourcesSha256': {str(f): hashlib.sha256(f.read_bytes()).hexdigest() for f in [*sources, Path(__file__)]}}
    (out / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
