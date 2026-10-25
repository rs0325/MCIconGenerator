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
    for mode in ['normal', 'zero-eof', 'read-error', 'exact-limit', 'over-limit', 'open-failure', 'reparse', 'close-failure']:
        result = subprocess.run([str(binary), mode], check=True, capture_output=True, text=True, timeout=5)
        value = json.loads(result.stdout)
        if mode in ['normal', 'zero-eof', 'exact-limit']:
            assert value['status'] == 'observed' and value['rows'][1]['parentIndex'] == 0
            assert value['rows'][0]['aces'][0]['rawMask'] == 0xc0020000
            assert value['rows'][0]['aces'][1]['unknown'] is True
        else:
            assert value['status'] == 'unknown'
            if mode == 'read-error': assert value['errorStage'] == 'stdin' and value['win32OrNtStatus'] == 5
            if mode == 'over-limit': assert value['errorStage'] == 'stdin-size'
    print('native fake API 8 cases PASS (not Windows acceptance)')
