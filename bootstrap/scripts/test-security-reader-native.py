"""実sourceをfake WinAPIへ接続し、相対open/保持handle/close/生maskを検査する。"""
import argparse, json, pathlib, subprocess, tempfile

parser = argparse.ArgumentParser(); parser.add_argument('--cxx', type=pathlib.Path, required=True); args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='mcicon-reader-fixture-') as folder:
    stage = pathlib.Path(folder)
    source = (root / 'src/main/native/windows-security-reader.cpp').read_text()
    source = '\n'.join(line for line in source.splitlines() if line not in ['#include <windows.h>', '#include <winternl.h>', '#include <aclapi.h>', '#include <sddl.h>'])
    (stage / 'reader-source.inc').write_text(source)
    binary = stage / 'fixture'
    subprocess.run([str(args.cxx), '-std=c++17', '-Wall', '-Wextra', '-Werror', '-include', str(root / 'src/test/native/security-api-fixture.h'), '-I', str(stage), '-I', str(root / 'src/main/native'), str(root / 'src/test/native/security-api-fixture.cpp'), '-o', str(binary)], check=True, timeout=30)
    modes = ['normal', 'zero-eof', 'read-error', 'exact-limit', 'over-limit', 'open-failure', 'reparse', 'close-failure', 'file', 'file-hardlink-zero', 'file-hardlink-two', 'file-hardlink-max', 'file-links-changed', 'file-links-late', 'file-delete-pending', 'file-standard-failure', 'file-type-pipe', 'file-type-unknown', 'file-type-error', 'file-directory', 'file-standard-directory', 'file-reparse', 'file-close-failure', 'file-open-failure', 'file-id-changed', 'file-security-failure', 'middle-file']
    for mode in modes:
        result = subprocess.run([str(binary), mode], check=True, capture_output=True, text=True, timeout=5)
        print(json.dumps({'mode': mode, 'stdout': result.stdout, 'stderr': result.stderr, 'exit': result.returncode, 'collected': True}), flush=True)
        value = json.loads(result.stdout)
        assert value['schemaVersion'] == 2
        if mode in ['normal', 'zero-eof', 'exact-limit', 'file']:
            assert value['status'] == 'observed' and value['rows'][1]['parentIndex'] == 0
            assert value['rows'][0]['aces'][0]['rawMask'] == 0xc0020000
            assert value['rows'][0]['aces'][1]['unknown'] is True
            assert value['rows'][0]['nodeType'] == 'directory' and value['rows'][0]['linkCount'] is None
            assert value['rows'][1]['nodeType'] == ('regular-file' if mode == 'file' else 'directory')
            assert value['rows'][1]['linkCount'] == (1 if mode == 'file' else None)
        else:
            assert value['status'] == 'unknown'
            if mode == 'read-error': assert value['errorStage'] == 'stdin' and value['win32OrNtStatus'] == 5
            if mode == 'over-limit': assert value['errorStage'] == 'stdin-size'
            if mode in ['file-hardlink-zero', 'file-hardlink-two', 'file-hardlink-max', 'file-links-changed', 'file-links-late']: assert value['errorStage'] == 'hardlink-count' and value['linkCount'] != 1
            if mode == 'file-standard-directory': assert value['errorStage'] == 'file-type' and value['directory'] is True
            if mode == 'file-standard-failure': assert value['errorStage'] == 'file-standard' and value['win32OrNtStatus'] == 5
            if mode in ['file-type-unknown', 'file-type-error']: assert value['errorStage'] == 'file-type' and value['fileType'] == 0 and value['win32OrNtStatus'] == (5 if mode == 'file-type-error' else 0)
    print(f'native fake API {len(modes)} cases PASS (not Windows acceptance)')
