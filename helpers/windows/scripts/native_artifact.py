"""包装前にnative PEと固定source/build記録を照合する。実OS受入や署名を代用しない。"""
import hashlib, json, pathlib, re, struct

TOOLCHAIN_SHA256 = 'a7a19b11048e89372637d26279cdb7c430ed3cf8c1e224310421312bf986ebf6'
BUILD_FLAGS = ['--no-default-config', '-rtlib=compiler-rt', '-unwindlib=libunwind', '-stdlib=libc++', '-fuse-ld=lld', '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror', '-static', '-municode']
LINK_FLAGS = ['-ladvapi32', '-lole32', '-Wl,--no-insert-timestamp']
LICENSE_PATHS = ['LICENSE.TXT', 'x86_64-w64-mingw32/share/mingw32/COPYING', 'x86_64-w64-mingw32/share/mingw32/COPYING.MinGW-w64.txt', 'x86_64-w64-mingw32/share/mingw32/COPYING.MinGW-w64-runtime.txt']

def require(ok, detail):
    if not ok: raise RuntimeError(detail)

def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()

def pe_imports(path):
    data = path.read_bytes(); require(64 <= len(data) <= 16777216 and data[:2] == b'MZ', 'PE長/署名不一致')
    def unpack(fmt, offset):
        require(0 <= offset <= len(data) - struct.calcsize(fmt), 'PE範囲外')
        return struct.unpack_from(fmt, data, offset)
    pe, = unpack('<I', 60); require(data[pe:pe + 4] == b'PE\0\0', 'PE署名不一致')
    machine, count = unpack('<HH', pe + 4); optional_size, characteristics = unpack('<HH', pe + 20)
    optional = pe + 24; magic, = unpack('<H', optional); subsystem, = unpack('<H', optional + 68)
    directories, = unpack('<I', optional + 108)
    require(machine == 0x8664 and magic == 0x20b and subsystem == 3 and 1 <= count <= 96 and optional_size >= 128 and directories >= 2 and characteristics & 2 and not characteristics & 0x2000, 'PE32+ AMD64 console必須')
    if directories >= 14:
        require(optional_size >= 224 and unpack('<II', optional + 216) == (0, 0), 'delay import未許可')
    sections = []
    for index in range(count):
        offset = optional + optional_size + index * 40
        virtual_size, virtual, raw_size, raw = unpack('<IIII', offset + 8)
        require(raw + raw_size <= len(data), 'PE section範囲外'); sections.append((virtual, virtual_size, raw, raw_size))
    def rva(value, size):
        found = [raw + value - virtual for virtual, virtual_size, raw, raw_size in sections if virtual <= value and value + size <= virtual + min(virtual_size, raw_size)]
        require(len(found) == 1, 'PE RVA不一致'); return found[0]
    imports_rva, imports_size = unpack('<II', optional + 120)
    require(20 <= imports_size <= 65536, 'PE import長不一致'); table = rva(imports_rva, imports_size); names = []
    for offset in range(table, table + imports_size - 19, 20):
        row = unpack('<IIIII', offset)
        if not any(row): break
        name = rva(row[3], 1); end = data.find(b'\0', name, min(name + 257, len(data)))
        require(end > name, 'PE import名不一致')
        try: value = data[name:end].decode('ascii').lower()
        except UnicodeDecodeError: raise RuntimeError('PE import文字不一致') from None
        require(value in {'kernel32.dll', 'advapi32.dll', 'ole32.dll', 'ucrtbase.dll'} or re.fullmatch(r'api-ms-win-crt-[a-z0-9-]+\.dll', value), '未許可runtime import: ' + value)
        require(value not in names, 'PE import重複'); names.append(value)
    else: raise RuntimeError('PE import終端欠落')
    require(bool(names), 'PE import欠落'); return sorted(names)

def verify(native, source, manifest_path):
    for path in [native, source, manifest_path]: require(path.is_file() and not path.is_symlink(), 'native入力file不一致')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    require(manifest['schemaVersion'] == 1 and manifest['target'] == 'x86_64-w64-mingw32', 'native build schema/target不一致')
    require(manifest['sourceSha256'] == digest(source), 'native source pin不一致')
    require(manifest['toolchainArchiveSha256'] == TOOLCHAIN_SHA256 and manifest['flags'] == BUILD_FLAGS + LINK_FLAGS, 'native toolchain/flags不一致')
    require(manifest['artifact'] == {'bytes': native.stat().st_size, 'sha256': digest(native), 'imports': pe_imports(native)}, 'native artifact pin不一致')
    require(manifest['acceptance'] == 'crossbuild-only-not-native-os-accepted', 'native未受入区分不一致')
    require(manifest_path.name == 'build.json', 'native build.json必須')
    notices = manifest['notices']
    require([row['path'] for row in notices] == ['licenses/' + path for path in LICENSE_PATHS], 'native license notice不一致')
    for row in notices:
        relative = pathlib.PurePosixPath(row['path']); path = manifest_path.parent / relative
        require(not relative.is_absolute() and '..' not in relative.parts and path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(manifest_path.parent.resolve()), 'native notice path不一致')
        require(path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], 'native notice pin不一致')
    return manifest
