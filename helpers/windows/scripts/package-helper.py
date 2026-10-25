"""Windows上の既存JDK21と明示した事前build PEから自己完結helperを生成する。"""
import argparse, json, pathlib, shutil, subprocess, sys
from native_artifact import digest, require, verify

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[3]
PROJECT_NOTICES = ['LICENSE', 'THIRD_PARTY_NOTICES.md', *['licenses/' + name + '.txt' for name in ['Apache-2.0', 'Commons-Codec', 'Commons-Compress', 'Commons-IO', 'Commons-Lang', 'XZ-for-Java']]]

def run(args): subprocess.run([str(value) for value in args], check=True, timeout=180)

def main():
    parser = argparse.ArgumentParser()
    for name in ['jdk', 'input', 'payload', 'native', 'native-source', 'native-build', 'output']: parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args(); require(sys.platform == 'win32', 'Windows上で生成してください'); require(not args.output.exists(), '既存出力の上書き拒否')
    native_build = verify(args.native, args.native_source, args.native_build)
    manifest = json.loads((args.payload / 'payload.json').read_text(encoding='utf-8'))
    require(manifest['schemaVersion'] == 1 and manifest['guestBridgeAbi'] == 1, 'payload ABI不一致')
    rows = manifest['files']; require(len({row['path'] for row in rows}) == len(rows), 'payload重複')
    require({'guestbridge.py'} <= {row['path'] for row in rows}, 'native/bridge不足')
    for row in rows:
        relative = pathlib.PurePosixPath(row['path']); source = args.payload / relative
        require(not relative.is_absolute() and '..' not in relative.parts and source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(args.payload.resolve()), 'payload path不一致')
        require(source.stat().st_size == row['bytes'] and digest(source) == row['sha256'], 'payload size/hash不一致')
    require(manifest['guestBase']['bridgeSha256'] == digest(args.payload / 'guestbridge.py'), 'guest bridge pin不一致')
    require(not any(row['path'] == 'windows-host.exe' or row['path'].startswith('native-build/') for row in rows), 'native入力とpayloadの衝突')
    for relative in PROJECT_NOTICES:
        source = PROJECT_ROOT / relative
        require(source.is_file() and not source.is_symlink(), 'project notice不足: ' + relative)
    args.output.mkdir(parents=True); work = args.output / 'staging'; work.mkdir()
    native = args.native
    inputs = work / 'input'; inputs.mkdir(); support = inputs / 'support'; support.mkdir()
    for row in rows:
        target = support / row['path']; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(args.payload / row['path'], target)
    shutil.copyfile(native, support / 'windows-host.exe')
    rows.append({'path': 'windows-host.exe', 'bytes': native.stat().st_size, 'sha256': digest(native)})
    for row in [{'path': args.native_build.name, 'bytes': args.native_build.stat().st_size, 'sha256': digest(args.native_build)}, *native_build['notices']]:
        relative = 'native-build/' + row['path']; target = support / relative
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(args.native_build.parent / row['path'], target)
        rows.append({'path': relative, 'bytes': row['bytes'], 'sha256': row['sha256']})
    for relative in PROJECT_NOTICES:
        source = PROJECT_ROOT / relative; name = 'licenses/mcicon/' + relative; target = support / name
        require(not target.exists(), 'project notice衝突: ' + name)
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
        rows.append({'path': name, 'bytes': source.stat().st_size, 'sha256': digest(source)})
    (support / 'payload.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for source in args.input.glob('*.jar'): shutil.copyfile(source, inputs / source.name)
    jars = list(inputs.glob('helper-windows-*.jar')); require(len(jars) == 1, 'helper JARが一意ではありません')
    runtime = work / 'runtime'
    run([args.jdk / 'bin/jlink.exe', '--add-modules', 'java.se,jdk.crypto.ec', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', runtime])
    run([args.jdk / 'bin/jpackage.exe', '--type', 'app-image', '--name', 'MCIconHelper', '--input', inputs, '--main-jar', jars[0].name, '--main-class', 'io.github.rs0325.mcicon.helper.windows.Main', '--runtime-image', runtime, '--dest', args.output / 'image'])
    image = args.output / 'image/MCIconHelper'; files = []
    for path in sorted(image.rglob('*')):
        require(not path.is_symlink(), 'app-image link拒否')
        if path.is_file(): files.append({'path': path.relative_to(image).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest(path)})
    for row in rows:
        path = image / 'app/support' / row['path']; require(path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], '最終payload不一致')
    (args.output / 'packaging.json').write_text(json.dumps({'status': 'packaging-only-not-os-accepted', 'entrypoint': 'MCIconHelper.exe', 'nativeBuild': {'manifestSha256': digest(args.native_build), 'sourceSha256': native_build['sourceSha256'], 'artifact': native_build['artifact'], 'signing': 'not-asserted'}, 'files': files, 'limits': ['Windows署名/SmartScreen/OS下限/実WSL未受入']}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__': main()
