#!/usr/bin/env python3
"""旧1.21.4専用QA agentを既存JDK21・既存ASMから作成して変換を検査する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--worker', type=Path, required=True, help='旧shield失敗世代のworker JAR')
    a = p.parse_args()
    source = Path(__file__).resolve().parent
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    asm = a.cache.resolve() / 'libraries/org/ow2/asm/asm/9.6/asm-9.6.jar'
    client = a.cache.resolve() / 'versions/1.21.4/client.jar'
    mappings = a.cache.resolve() / 'versions/1.21.4/mappings.txt'
    worker = a.worker.resolve()
    if hashlib.sha256(client.read_bytes()).hexdigest() != 'c17c450c6e72cc51297daa57ce38f800aa01cf022b743daa21a0512d326d894e':
        raise ValueError('旧1.21.4のclientではありません')
    for path, digest in [(worker, '8b85e11a940ef3497937e0af1d9f3571e2ba2842a6cd2b11dafba027805acb42'),
                         (mappings, '48b502ccc5e855b49da8aa9c0c0d7c565bec54c9f5da3a3664528aff7b9fdf23'),
                         (asm, '3c6fac2424db3d4a853b669f4e3d1d9c3c552235e19a319673f887083c2303a1')]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest: raise ValueError('固定QA入力不一致: ' + str(path))
    classes = out / 'classes'
    classes.mkdir()
    sources = [source / 'StartupStageAgent.java', source / 'StartupStageCheck.java']
    subprocess.run([str(java / 'javac'), '--release', '21', '-encoding', 'UTF-8', '-cp', str(asm), '-d', str(classes), *map(str, sources)], check=True, timeout=60)
    check = subprocess.run([str(java / 'java'), '-cp', os.pathsep.join(map(str, [classes, asm, worker])), 'mcicon.qa.StartupStageCheck', str(client), str(out), str(mappings)], capture_output=True, text=True, check=True, timeout=30)
    jar = out / 'startup-stage.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: mcicon.qa.StartupStageAgent\n\n')
        for path in sorted(classes.rglob('StartupStageAgent*.class')):
            z.write(path, path.relative_to(classes).as_posix())
        with zipfile.ZipFile(asm) as dependency:
            for name in dependency.namelist():
                if name.startswith('org/objectweb/asm/') and name.endswith('.class'):
                    z.writestr(name, dependency.read(name))
    # JVMはtransformer例外を無視するため、実際に非対応classで終了97になることも確認する。
    fixture = out / 'fixture/net/minecraft/client/main/Main.java'
    fixture.parent.mkdir(parents=True)
    fixture.write_text('package net.minecraft.client.main; public class Main { public static void main(String[] args) { System.exit(0); } }\n')
    subprocess.run([str(java / 'javac'), '-d', str(out / 'fixture'), str(fixture)], check=True, timeout=30)
    log = out / 'rejected-stages.tsv'
    rejected = subprocess.run([str(java / 'java'), '-javaagent:' + str(jar) + '=' + str(log), '-cp', str(out / 'fixture'), 'net.minecraft.client.main.Main'], capture_output=True, timeout=30)
    if rejected.returncode != 97 or [line.split('\t')[0] for line in log.read_text().splitlines()] != ['agent.ready', 'agent.transform.failed']:
        raise AssertionError('非対応classを無計装の成功として通過させた')
    report = {'passed': True, 'check': check.stdout, 'unsupportedClassExitCode': rejected.returncode,
              'agentSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'inputsSha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in [client, mappings, worker, asm, *sources]}}
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
