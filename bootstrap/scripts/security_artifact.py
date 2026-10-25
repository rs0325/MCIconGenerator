"""bootstrap 専用 reader の固定build入力と PE を検査する。"""
import hashlib, json, pathlib, re, struct

TOOLCHAIN_SHA256 = 'a7a19b11048e89372637d26279cdb7c430ed3cf8c1e224310421312bf986ebf6'
FLAGS = ['--no-default-config', '-rtlib=compiler-rt', '-unwindlib=libunwind', '-stdlib=libc++', '-fuse-ld=lld', '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror', '-ferror-limit=10', '-static', '-municode', '-ladvapi32', '-lntdll', '-Wl,--no-insert-timestamp']
TOOLCHAIN_INPUTS = [{'path': 'bin/clang-target-wrapper.sh', 'sha256': 'c9b86311ade81d53235c93fafabdf98328d094de344ea9a9038e0dab6695ee9f'}, {'path': 'bin/clang-23', 'sha256': '88f9ff254e4b1c8456328155e1f5c761ff4d5499033f704975dc1966b24fd7a5'}, {'path': 'bin/lld', 'sha256': '783b770d8fdf51c86917c660edafe71a624f03a53d2bc83f2e2010628b816895'}, {'path': 'bin/llvm-readobj', 'sha256': '251c407aea699dc3640b51af6872d8647cf61c486a4042b9b98793be21130f96'}, {'path': 'lib/libclang-cpp.23.1.dylib', 'sha256': '8a9852792374a3aad20acf1064ddc2543da3c06ef91b882f7f96f05e342ef698'}, {'path': 'lib/libLLVM.23.1.dylib', 'sha256': 'dd99049663ef2373656c533b99939204bf06d32ae8a8fa9ccb5e9398c863d52c'}]
LICENSES = ['LICENSE.TXT', 'x86_64-w64-mingw32/share/mingw32/COPYING', 'x86_64-w64-mingw32/share/mingw32/COPYING.MinGW-w64.txt', 'x86_64-w64-mingw32/share/mingw32/COPYING.MinGW-w64-runtime.txt']

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
        require(value in {'kernel32.dll', 'advapi32.dll', 'ntdll.dll', 'ucrtbase.dll'} or re.fullmatch(r'api-ms-win-crt-[a-z0-9-]+\.dll', value), '未許可runtime import: ' + value)
        require(value not in names, 'PE import重複'); names.append(value)
    else: raise RuntimeError('PE import終端欠落')
    require(bool(names), 'PE import欠落'); return sorted(names)

def verify(folder, source):
    native = folder / 'windows-security-reader.exe'
    manifest = folder / 'build.json'
    for path in [native, manifest, source, source.with_name('security-protocol.h')]:
        require(path.is_file() and not path.is_symlink(), 'reader固定入力の通常fileが必要です')
    value = json.loads(manifest.read_text(encoding='utf-8'))
    require(value['schemaVersion'] == 1 and value['target'] == 'x86_64-w64-mingw32', 'reader build target不一致')
    require(value['toolchainArchiveSha256'] == TOOLCHAIN_SHA256 and value['toolchainInputs'] == TOOLCHAIN_INPUTS and value['flags'] == FLAGS, 'reader toolchain/flags不一致')
    require(value['sources'] == [{'path': p.name, 'sha256': digest(p)} for p in [source, source.with_name('security-protocol.h')]], 'reader source不一致')
    require(value['artifact'] == {'bytes': native.stat().st_size, 'sha256': digest(native), 'imports': pe_imports(native)}, 'reader PE不一致')
    require(value['acceptance'] == 'crossbuild-only-not-windows-accepted', 'reader受入区分不一致')
    require([r['path'] for r in value['licenses']] == ['licenses/' + name for name in LICENSES], 'reader license一覧不一致')
    for row in value['licenses']:
        path = folder / row['path']
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], 'reader license不一致')
    return value
