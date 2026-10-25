"""検査済LLVM-MinGWで別reader PEを生成する。取得・導入・Windows実行はしない。"""
import argparse, importlib.util, json, os, pathlib, shutil, sys
from security_artifact import TOOLCHAIN_SHA256, FLAGS, LICENSES, TOOLCHAIN_INPUTS, digest, require, pe_imports


def main():
    parser = argparse.ArgumentParser()
    for name in ['toolchain', 'archive', 'output']: parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    for name in ['toolchain', 'archive', 'output']: setattr(args, name, getattr(args, name).resolve())
    repo = pathlib.Path(__file__).resolve().parents[2]
    source = repo / 'bootstrap/src/main/native/windows-security-reader.cpp'
    inputs = [source, source.with_name('security-protocol.h')]
    require(sys.platform == 'darwin' and not args.output.exists(), 'Macの新規出力だけを使用します')
    require(digest(args.archive) == TOOLCHAIN_SHA256, 'reader toolchain archive不一致')
    for row in TOOLCHAIN_INPUTS:
        path = (args.toolchain / row['path']).resolve()
        require(path.is_file() and path.is_relative_to(args.toolchain) and digest(path) == row['sha256'], 'reader実toolchain pin不一致')
    compiler = args.toolchain / 'bin/x86_64-w64-mingw32-clang++'
    require(compiler.is_file() and compiler.resolve().is_relative_to(args.toolchain), 'reader compiler位置不一致')
    # 既native buildの有限process監督をそのまま使い、別runnerを作らない。
    scripts = repo / 'helpers/windows/scripts'; sys.path.insert(0, str(scripts))
    spec = importlib.util.spec_from_file_location('helper_native_build', scripts / 'build-native.py')
    helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
    args.output.mkdir(); temporary = args.output / 'tmp'; temporary.mkdir()
    env = {'PATH': '/usr/bin:/bin', 'TMPDIR': str(temporary), 'TMP': str(temporary), 'TEMP': str(temporary)}
    if 'HOME' in os.environ: env['HOME'] = os.environ['HOME']
    pins = [{'path': p.name, 'sha256': digest(p)} for p in inputs]
    native = args.output / 'windows-security-reader.exe'
    helper.run([compiler, *FLAGS, source, '-o', native], args.output, env)
    require(pins == [{'path': p.name, 'sha256': digest(p)} for p in inputs], 'build中にreader sourceが変わりました')
    for path in [args.output / 'compile.stdout', args.output / 'compile.stderr']:
        require(path.stat().st_size <= 65536, 'compiler logが上限外です')
    licenses = []
    for name in LICENSES:
        original = args.toolchain / name; target = args.output / 'licenses' / name
        require(original.is_file() and original.resolve().is_relative_to(args.toolchain), 'reader licenseがありません')
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(original, target)
        licenses.append({'path': target.relative_to(args.output).as_posix(), 'bytes': target.stat().st_size, 'sha256': digest(target)})
    value = {'schemaVersion': 1, 'target': 'x86_64-w64-mingw32', 'toolchainArchiveSha256': TOOLCHAIN_SHA256, 'sources': pins, 'flags': FLAGS, 'toolchainInputs': TOOLCHAIN_INPUTS,
             'artifact': {'bytes': native.stat().st_size, 'sha256': digest(native), 'imports': pe_imports(native)}, 'licenses': licenses,
             'acceptance': 'crossbuild-only-not-windows-accepted', 'signing': 'not-asserted'}
    with (args.output / 'build.json').open('x', encoding='utf-8') as stream: json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')


if __name__ == '__main__': main()
