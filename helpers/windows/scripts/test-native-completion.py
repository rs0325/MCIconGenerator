"""製品nativeの回収/起動pathをfake WinAPIへ接続する。実Windowsの代用ではない。"""
import argparse, pathlib, subprocess, tempfile

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cxx', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    source = (root / 'src/main/native/windows-host.cpp').read_text()
    client = next(line.strip() for line in source.splitlines() if line.strip().startswith('ULONGLONG end=0;int exit;Completion completion;'))
    start = source.index('static DWORD remainingUntil(')
    end = source.index('static int facts()', start)
    fixture = root / 'src/test/native/completion-fixture.cpp'
    with tempfile.TemporaryDirectory(prefix='mcicon-native-fixture-') as directory:
        stage = pathlib.Path(directory)
        (stage / 'native-completion.inc').write_text(source[start:end])
        (stage / 'native-client.inc').write_text(client)
        binary = stage / 'fixture'
        subprocess.run([str(args.cxx), '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(stage), str(fixture), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        observation = source[source.index('static void observeJob('):source.index('static DWORD remainingUntil(')]
        (stage / 'native-observation.inc').write_text(observation)
        fixture = root / 'src/test/native/observation-fixture.cpp'
        subprocess.run([str(args.cxx), '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(stage), str(fixture), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        functions = ['quoted', 'extended', 'applicationPath']
        lines = source.splitlines()
        (stage / 'native-application.inc').write_text('\n'.join(next(line for line in lines if line.startswith('static std::wstring ' + name + '(')) for name in functions))
        create = next(line.strip() for line in lines if line.strip().startswith('std::wstring command;'))
        assert 'application=applicationPath(argv[6])' in create and 'CreateProcessW(application.c_str(),command.data(),' in create
        (stage / 'native-create.inc').write_text(create)
        fixture = root / 'src/test/native/application-fixture.cpp'
        subprocess.run([str(args.cxx), '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(stage), str(fixture), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        (stage / 'native-completion-type.inc').write_text(next(line for line in lines if line.startswith('struct Completion ')))
        (stage / 'native-startup.inc').write_text(source[source.index('struct Startup {'):source.index('static int executeStarted(')])
        (stage / 'native-wsl.inc').write_text(source[source.index('static int wslExecute('):])
        client = next(line.strip() for line in lines if line.strip().startswith('ULONGLONG end=0;int exit;Completion completion;'))
        (stage / 'native-client.inc').write_text(client)
        fixture = root / 'src/test/native/wsl-fixture.cpp'
        subprocess.run([str(args.cxx), '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I', str(stage), str(fixture), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)

if __name__ == '__main__': main()
