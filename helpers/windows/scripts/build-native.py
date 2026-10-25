"""検査済LLVM-MinGWからWindows x64 PEと包装用pinを生成する。取得/設定変更はしない。"""
import argparse, json, os, pathlib, shutil, signal, subprocess, sys, time
from native_artifact import BUILD_FLAGS, LINK_FLAGS, LICENSE_PATHS, TOOLCHAIN_SHA256, digest, pe_imports, require

def run(args, cwd, env):
    process = None; original = None
    report = {'pid': None, 'started': False, 'collected': False, 'timeout': False, 'exit': None, 'error': None, 'collectionError': None, 'ownership': 'not-started', 'signalBeforeReap': False}
    with (cwd / 'compile.stdout').open('xb') as out, (cwd / 'compile.stderr').open('xb') as err:
        try:
            process = subprocess.Popen([str(value) for value in args], cwd=cwd, env=env, stdout=out, stderr=err, start_new_session=True)
            report.update(pid=process.pid, started=True, ownership='waiting')
            process.wait(timeout=120)
            report.update(collected=process.returncode is not None, ownership='reaped')
        except BaseException as failure:
            original = failure; report.update(timeout=isinstance(failure, subprocess.TimeoutExpired), error=type(failure).__name__ + ': ' + str(failure))
        finally:
            if process is not None and not report['collected']:
                end = time.monotonic() + 10
                try:
                    if process.returncode is not None:
                        report.update(collected=True, ownership='already-reaped-no-signal')
                    else:
                        pid, status = os.waitpid(process.pid, os.WNOHANG)
                        if pid == process.pid:
                            process.returncode = os.waitstatus_to_exitcode(status); report.update(collected=True, ownership='reaped-by-probe-no-signal')
                        elif pid == 0:
                            report['ownership'] = 'unreaped-child-proven'
                            try: os.killpg(process.pid, signal.SIGKILL); report['signalBeforeReap'] = True
                            except ProcessLookupError: pass
                            process.wait(timeout=max(0, end - time.monotonic())); report['collected'] = process.returncode is not None
                        else: report['ownership'] = 'unknown-no-signal'
                except BaseException as cleanup:
                    report['collectionError'] = type(cleanup).__name__ + ': ' + str(cleanup)
                    proven = report['ownership'] == 'unreaped-child-proven'
                    if not proven: report['ownership'] = 'unknown-no-signal'
                    try:
                        process.wait(timeout=max(0, end - time.monotonic()))
                        report['collected'] = proven and process.returncode is not None
                    except BaseException: report['collected'] = False
                report['exit'] = process.returncode if report['collected'] else None
            elif process is not None: report['exit'] = process.returncode
            report['unknown'] = original is not None or not report['collected']
            try: (cwd / 'compile-process.json').write_text(json.dumps(report) + '\n')
            except BaseException as record_error:
                if original is None: raise
                original.add_note('build終了記録の保存にも失敗しました: ' + type(record_error).__name__)
    if original is not None: raise original
    require(report['exit'] == 0, 'native build失敗。compile.stderrを確認してください')

def main():
    parser = argparse.ArgumentParser()
    for name in ['toolchain', 'archive', 'source', 'output']: parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    for name in ['toolchain', 'archive', 'source', 'output']: setattr(args, name, getattr(args, name).resolve())
    require(sys.platform == 'darwin', 'Mac専用buildです')
    require(not args.output.exists(), '既存出力の上書き拒否'); require(digest(args.archive) == TOOLCHAIN_SHA256, 'toolchain archive pin不一致')
    compiler = args.toolchain / 'bin/x86_64-w64-mingw32-clang++'
    require(compiler.resolve().is_relative_to(args.toolchain.resolve()) and compiler.is_file(), 'compiler path不一致')
    args.output.mkdir(parents=True); temporary = args.output / 'tmp'; temporary.mkdir()
    env = {'PATH': '/usr/bin:/bin', 'TMPDIR': str(temporary), 'TMP': str(temporary), 'TEMP': str(temporary)}
    if 'HOME' in os.environ: env['HOME'] = os.environ['HOME']
    source_sha = digest(args.source); native = args.output / 'windows-host.exe'
    run([compiler, *BUILD_FLAGS, args.source.resolve(), '-o', native.resolve(), *LINK_FLAGS], args.output, env)
    require(digest(args.source) == source_sha, 'build中のsource変更')
    notices = []
    for relative in LICENSE_PATHS:
        source = args.toolchain / relative; target = args.output / 'licenses' / relative
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
        notices.append({'path': target.relative_to(args.output).as_posix(), 'bytes': target.stat().st_size, 'sha256': digest(target)})
    manifest = {'schemaVersion': 1, 'target': 'x86_64-w64-mingw32', 'toolchainArchiveSha256': TOOLCHAIN_SHA256, 'sourceSha256': source_sha, 'flags': BUILD_FLAGS + LINK_FLAGS, 'toolchainInputs': [{'path': name, 'sha256': digest((args.toolchain / name).resolve())} for name in ['bin/clang-target-wrapper.sh', 'bin/clang-23', 'bin/lld', 'bin/llvm-readobj', 'lib/libclang-cpp.23.1.dylib', 'lib/libLLVM.23.1.dylib']], 'artifact': {'bytes': native.stat().st_size, 'sha256': digest(native), 'imports': pe_imports(native)}, 'notices': notices, 'acceptance': 'crossbuild-only-not-native-os-accepted', 'signing': 'not-asserted'}
    (args.output / 'build.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__': main()
