"""実Windowsのfile identity失敗を確認する。WSL・VM・ACL変更は使わない。"""
import argparse
import ctypes
from ctypes import wintypes
import json
import pathlib
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', type=pathlib.Path, required=True)
    parser.add_argument('--temporary', type=pathlib.Path, required=True)
    args = parser.parse_args()
    native = args.native.resolve()
    args.temporary.mkdir(parents=True, exist_ok=True)

    def observe(path, kind=None, operation=None, codes=None):
        result = subprocess.run([str(native), 'file-identity', str(path)], capture_output=True, timeout=2)
        if kind is None:
            assert result.returncode == 0 and result.stderr == b''
            value = json.loads(result.stdout)
            assert set(value) == {'schemaVersion', 'volumeSerial', 'fileId'} and value['schemaVersion'] == 1
            assert len(value['fileId']) == 32 and value['volumeSerial'].isdigit()
        else:
            assert result.returncode == 1 and result.stdout == b''
            value = json.loads(result.stderr)
            assert set(value) == {'schemaVersion', 'phase', 'kind', 'operation', 'win32Code'}
            assert value['schemaVersion'] == 1 and value['phase'] == 'file-identity'
            assert value['kind'] == kind and value['operation'] == operation
            assert value['win32Code'] in codes if codes else value['win32Code'] is None
        return value

    with tempfile.TemporaryDirectory(dir=args.temporary) as directory:
        root = pathlib.Path(directory).resolve()
        file = root / 'owned'; file.write_bytes(b'fixture')
        first = observe(file)
        assert observe(file) == first
        observe(root / 'missing', 'WIN32', 'CreateFileW', {2})
        observe(root, 'WIN32', 'CreateFileW', {5})
        link = root / 'link'; link.hardlink_to(file)
        observe(file, 'CONTRACT', 'FileStandardInfo'); link.unlink()
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
        kernel.CreateFileW.restype = wintypes.HANDLE
        kernel.SetFileInformationByHandle.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        kernel.SetFileInformationByHandle.restype = wintypes.BOOL
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.CreateFileW(str(file), 0x10000, 7, None, 3, 0, None)
        assert handle != ctypes.c_void_p(-1).value
        try:
            delete = wintypes.BOOL(True)
            assert kernel.SetFileInformationByHandle(handle, 4, ctypes.byref(delete), ctypes.sizeof(delete))
            observe(file, 'WIN32', 'CreateFileW', {5, 303})
        finally:
            assert kernel.CloseHandle(handle)
    print('実Windows file identity: 成功・不存在・directory・hardlink・削除待ちの5境界で成功')


if __name__ == '__main__':
    main()
