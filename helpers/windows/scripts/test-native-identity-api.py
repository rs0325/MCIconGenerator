"""製品fileIdentityをfake WinAPIで検証する。実OS試験とは区別する。"""
import argparse
import pathlib
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cxx', type=pathlib.Path, required=True)
    parser.add_argument('--temporary', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    source = (root / 'src/main/native/windows-host.cpp').read_text(encoding='utf-8')
    args.temporary.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.temporary) as directory:
        stage = pathlib.Path(directory).resolve()
        (stage / 'native-identity.inc').write_text(source[source.index('static int fileIdentity('):source.index('int wmain(')], encoding='utf-8')
        binary = stage / 'identity-api.exe'
        flags = ['--no-default-config', '-rtlib=compiler-rt', '-unwindlib=libunwind', '-stdlib=libc++', '-fuse-ld=lld', '-static'] if sys.platform == 'win32' else []
        subprocess.run([str(args.cxx.resolve()), *flags, '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(stage), str(root / 'src/test/native/identity-api-fixture.cpp'), '-o', str(binary)], check=True, timeout=120)
        subprocess.run([str(binary)], check=True, timeout=5)


if __name__ == '__main__':
    main()
