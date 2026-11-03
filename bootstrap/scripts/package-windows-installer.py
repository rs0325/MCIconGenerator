"""既存Windows候補をuser scope MSIと未公開winget候補へ包装し、静的監査する。"""
from pathlib import Path
import argparse
import ctypes
from ctypes import wintypes
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

from security_artifact import LICENSES, digest, require

spec = importlib.util.spec_from_file_location('windows_image', Path(__file__).with_name('package-windows-bootstrap.py'))
image_packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image_packager)
REPO = image_packager.REPO
NS = 'http://schemas.microsoft.com/wix/2006/wi'
UPGRADE = 'C93C85D7-16A6-5F82-A210-5C42F5DD2792'
UNVERIFIED = ['コード署名', '実install/upgrade/uninstall', 'PATHの実反映', '配置ACL/reparse拒否',
              '標準user/権限拒否時の実挙動', '実winget schema検証/外部審査/公開', '正式署名seedでの起動']


def write_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')


def identifier(prefix, name):
    return prefix + uuid.uuid5(uuid.UUID(UPGRADE), name).hex


def input_image(candidate, version):
    """前段の完了記録・ZIP・image全bytesとlicense正本を結ぶ。署名検証の再実行ではない。"""
    image_packager.tree(candidate)
    record = json.loads((candidate / 'packaging.json').read_text(encoding='utf-8'))
    info = record['package']
    require(record['status'] == 'windows-app-image-and-archive-candidate-not-installer-accepted', '前段の完了記録が必要です')
    require(info['bootstrapVersion'] == version and info['owner'] in ('installer', 'winget') and
            info['host'] == {'os': 'windows', 'arch': 'x86_64'}, '同版Windows x64 installer/winget所有imageが必要です')
    image = candidate / 'image/mcicon'
    require(image_packager.inventory(image) == record['files'], 'image全bytesが前段記録と不一致です')
    archive = candidate / 'candidate.zip'
    require(record['archive'] == archive.name and archive.stat().st_size == record['bytes'] and
            digest(archive) == record['sha256'], '前段ZIPのchecksumが不一致です')
    with image_packager.zipfile.ZipFile(archive) as bundle:
        entries = [row.filename for row in bundle.infolist() if not row.is_dir()]
        require(len(entries) == len(set(entries)) and set(entries) == {row['path'] for row in record['files']}, '前段ZIPの一覧が不一致です')
        for row in record['files']:
            require(bundle.read(row['path']) == (image / row['path']).read_bytes(), '前段ZIPとimageが不一致です')
    check_image(image, info)
    return image, record


def check_image(image, info):
    version = info['bootstrapVersion']
    require(info['bootstrapProtocol'] == 4, 'bootstrap protocol 4が必要です')
    image_packager.read_trust(image / 'app/trust-set.bin')
    require(json.loads((image / 'app/bootstrap-package.json').read_text(encoding='utf-8')) == info, 'package metadataが不一致です')
    required = ['mcicon.exe', 'runtime/bin/java.exe', 'app/mcicon.cfg', 'app/support/windows-security-reader.exe',
                'app/seed/stable.json', 'app/seed/stable.json.sig', 'app/seed/tool.zip', 'app/trust-set.bin',
                'app/bootstrap-' + version + '.jar']
    for name in required: require((image / name).is_file(), '配布必須fileが不足しています: ' + name)
    licenses = [(REPO / name, 'app/' + name) for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md')]
    licenses += [(path, 'app/licenses/' + path.relative_to(REPO / 'licenses').as_posix())
                 for path in image_packager.tree(REPO / 'licenses') if path.is_file()]
    licenses += [(REPO / name, 'app/support/security-reader/licenses/mcicon/' + name) for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md')]
    for source, target in licenses:
        require((image / target).is_file() and (image / target).read_bytes() == source.read_bytes(), 'license正本と不一致です: ' + target)
    for name in LICENSES:
        require((image / 'app/support/security-reader/licenses' / name).is_file(), 'reader licenseが不足しています')
    reader = json.loads((image / 'app/support/windows-security-reader.json').read_text(encoding='utf-8'))
    expected_licenses = {'security-reader/licenses/' + name for name in LICENSES} | {
        'security-reader/licenses/mcicon/' + name for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md')}
    require({row['path'] for row in reader['licenses']} == expected_licenses, 'reader license記録の集合が不一致です')
    for row in reader['licenses']:
        path = image / 'app/support' / row['path']
        require(path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], 'reader license記録とbytesが不一致です')
    require(any(path.is_file() for path in image_packager.tree(image / 'runtime/legal')), '同梱Javaのlegal通知が不足しています')


def wix_source(image, files, version, product):
    """user PATHと配布fileだけを所有するWiX定義を作る。実行用custom actionは持たない。"""
    ET.register_namespace('', NS)
    def add(parent, tag, **attrs): return ET.SubElement(parent, '{' + NS + '}' + tag, attrs)
    root = ET.Element('{' + NS + '}Wix')
    app = add(root, 'Product', Id=product, Name='MCIconGenerator Bootstrap', Language='1033', Version=version,
              Manufacturer='rs0325', UpgradeCode=UPGRADE)
    add(app, 'Package', InstallerVersion='500', Compressed='yes', InstallScope='perUser', InstallPrivileges='limited',
        Platform='x64', Description='MCIconGenerator Bootstrap candidate')
    add(app, 'MajorUpgrade', DowngradeErrorMessage='A newer bootstrap is already installed.')
    add(app, 'MediaTemplate', EmbedCab='yes')
    add(app, 'Property', Id='ARPNOMODIFY', Value='1')
    add(app, 'Condition', Message='This candidate supports per-user installation on Windows x64 only.').text = 'NOT ALLUSERS AND VersionNT64'
    target = add(app, 'Directory', Id='TARGETDIR', Name='SourceDir')
    local = add(target, 'Directory', Id='LocalAppDataFolder')
    programs = add(local, 'Directory', Id='ProgramsFolder', Name='Programs')
    install = add(programs, 'Directory', Id='INSTALLFOLDER', Name='MCIconGenerator')
    directories = {'.': install}
    for row in files:
        parent = Path(row['path']).parent
        for directory in reversed([parent, *parent.parents]):
            name = directory.as_posix()
            if name not in directories:
                directories[name] = add(directories[directory.parent.as_posix()], 'Directory',
                                        Id=identifier('D', name), Name=directory.name)
    feature = add(app, 'Feature', Id='Bootstrap', Title='Bootstrap', Level='1')
    for name, directory in directories.items():
        key = identifier('C', name)
        component = add(directory, 'Component', Id=key, Guid=str(uuid.uuid5(uuid.UUID(UPGRADE), 'component/' + name)).upper(), Win64='yes')
        add(component, 'RegistryValue', Id=identifier('K', name), Root='HKCU', Key='Software\\rs0325\\MCIconGenerator\\Components', Name=key,
            Type='string', Value=version, KeyPath='yes')
        add(component, 'RemoveFolder', Id=identifier('R', name), On='uninstall')
        if name == '.':
            add(component, 'Environment', Id='UserPath', Name='PATH', Value='[INSTALLFOLDER]', Action='set', Part='last', System='no', Permanent='no')
        for row in files:
            if Path(row['path']).parent.as_posix() == name:
                add(component, 'File', Id=identifier('F', row['path']), Source=str(image / row['path']), Name=Path(row['path']).name)
        add(feature, 'ComponentRef', Id=key)
    # Programs自体は他製品と共有するため、空のときだけ除去する。
    add(next(iter(install.findall('{' + NS + '}Component'))), 'RemoveFolder', Id='RemovePrograms', Directory='ProgramsFolder', On='uninstall')
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def manifest(version, product, sha256):
    # JSONはYAML 1.2の部分集合。未確定値は有効な配布URL/hashへ偽装しない。
    return {'PackageIdentifier': 'rs0325.MCIconGenerator', 'PackageVersion': version, 'PackageLocale': 'ja-JP',
            'Publisher': 'rs0325', 'PackageName': 'MCIconGenerator Bootstrap', 'License': 'MIT',
            'ShortDescription': 'Minecraftアイコン生成CLIのJava同梱bootstrap', 'InstallerType': 'msi', 'Scope': 'user',
            'Commands': ['mcicon'], 'UpgradeBehavior': 'install', 'Installers': [{'Architecture': 'x64',
            'InstallerUrl': 'PENDING_PUBLIC_HTTPS_URL', 'InstallerSha256': sha256.upper(), 'ProductCode': '{' + product + '}'}],
            'ManifestType': 'singleton', 'ManifestVersion': '1.6.0'}


def run(args):
    result = subprocess.run([str(arg) for arg in args], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=120)
    output = result.stdout.decode(errors='replace')
    if result.returncode:
        print(output); result.check_returncode()
    warnings = [line for line in output.splitlines() if 'warning ' in line]
    for warning in warnings:
        if 'warning LGHT1076 : ICE91:' not in warning: print(warning)
    ice91 = [warning for warning in warnings if 'warning LGHT1076 : ICE91:' in warning]
    if ice91: print(ice91[0]); print('同種のICE91警告件数: ' + str(len(ice91)))


def msi_tables(path):
    """Windows Installerをread-onlyで開き、監査対象tableを読む。install APIは呼ばない。"""
    api = ctypes.WinDLL('msi')
    handle = wintypes.UINT
    definitions = {'MsiOpenDatabaseW': [wintypes.LPCWSTR, wintypes.LPCWSTR, ctypes.POINTER(handle)],
                   'MsiDatabaseOpenViewW': [handle, wintypes.LPCWSTR, ctypes.POINTER(handle)],
                   'MsiViewExecute': [handle, handle], 'MsiViewFetch': [handle, ctypes.POINTER(handle)],
                   'MsiRecordGetFieldCount': [handle], 'MsiRecordGetStringW': [handle, wintypes.UINT, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)],
                   'MsiCloseHandle': [handle]}
    for name, args in definitions.items():
        getattr(api, name).argtypes = args; getattr(api, name).restype = wintypes.UINT
    def check(code): require(code == 0, 'MSI静的読取が失敗しました: ' + str(code))
    database = handle(); check(api.MsiOpenDatabaseW(str(path), None, ctypes.byref(database)))
    def query(sql):
        view = handle(); check(api.MsiDatabaseOpenViewW(database, sql, ctypes.byref(view)))
        rows = []
        try:
            check(api.MsiViewExecute(view, 0))
            while True:
                record = handle(); code = api.MsiViewFetch(view, ctypes.byref(record))
                if code == 259: return sorted(rows)
                check(code)
                try:
                    row = []
                    for index in range(1, api.MsiRecordGetFieldCount(record) + 1):
                        length = wintypes.DWORD(0)
                        code = api.MsiRecordGetStringW(record, index, None, ctypes.byref(length))
                        require(code in (0, 234), 'MSI field長を読めません')
                        buffer = ctypes.create_unicode_buffer(length.value + 1); length.value += 1
                        check(api.MsiRecordGetStringW(record, index, buffer, ctypes.byref(length))); row.append(buffer.value)
                    rows.append(row)
                finally: api.MsiCloseHandle(record)
        finally: api.MsiCloseHandle(view)
    try:
        names = {row[0] for row in query('SELECT `Name` FROM `_Tables`')}
        return {name: query('SELECT * FROM `' + name + '`') if name in names else []
                for name in ('Property', 'Directory', 'Component', 'File', 'Environment', 'Registry', 'RemoveFile', 'CustomAction', 'LaunchCondition', 'Upgrade', 'InstallExecuteSequence')}
    finally: api.MsiCloseHandle(database)


def check_tables(tables, files, version, product):
    properties = dict(tables['Property'])
    require(properties.get('ProductVersion') == version and properties.get('ProductCode') == '{' + product + '}' and
            properties.get('UpgradeCode') == '{' + UPGRADE + '}' and not properties.get('ALLUSERS'), 'MSI版/識別子/user scopeが不一致です')
    require(not tables['CustomAction'], 'MSI custom actionは許可しません')
    require(any(row[0] == 'NOT ALLUSERS AND VersionNT64' for row in tables['LaunchCondition']), 'user/x64限定の起動条件が不足しています')
    require(tables['Environment'] == [['UserPath', '=-PATH', '[~];[INSTALLFOLDER]', identifier('C', '.')]], 'MSI user PATHの追加/削除契約が不一致です')
    expected = {identifier('F', row['path']): row for row in files}
    require(len(tables['File']) == len(expected) and {row[0] for row in tables['File']} == set(expected), 'MSI配布file集合が不一致です')
    for row in tables['File']:
        path = Path(expected[row[0]]['path'])
        require(row[1] == identifier('C', path.parent.as_posix()) and row[2].split('|')[-1] == path.name and
                int(row[3]) == expected[row[0]]['bytes'], 'MSI file配置/名/sizeが不一致です')
    names = {'.'} | {parent.as_posix() for row in files for parent in Path(row['path']).parents}
    def directory(name): return 'INSTALLFOLDER' if name == '.' else identifier('D', name)
    directories = [['TARGETDIR', '', 'SourceDir'], ['LocalAppDataFolder', 'TARGETDIR', '.'],
                   ['ProgramsFolder', 'LocalAppDataFolder', 'Programs'], ['INSTALLFOLDER', 'ProgramsFolder', 'MCIconGenerator']]
    directories += [[directory(name), directory(Path(name).parent.as_posix()), Path(name).name] for name in names if name != '.']
    require(sorted([[*row[:2], row[2].split('|')[-1]] for row in tables['Directory']]) == sorted(directories), 'MSI配置directoryが不一致です')
    components = [[identifier('C', name), '{' + str(uuid.uuid5(uuid.UUID(UPGRADE), 'component/' + name)).upper() + '}',
                   directory(name), '260', '', identifier('K', name)] for name in names]
    require(tables['Component'] == sorted(components), 'MSI componentの配置/所有が不一致です')
    registry = [[identifier('K', name), '1', 'Software\\rs0325\\MCIconGenerator\\Components', identifier('C', name),
                 version, identifier('C', name)] for name in names]
    require(tables['Registry'] == sorted(registry), 'HKCU製品key以外への書込みは許可しません')
    removal = [[identifier('R', name), identifier('C', name), '', directory(name), '2'] for name in names]
    removal.append(['RemovePrograms', identifier('C', '.'), '', 'ProgramsFolder', '2'])
    require(tables['RemoveFile'] == sorted(removal), 'uninstallは配布先の空directoryだけを除去します')


def extract(wix, msi, output):
    require(not output.exists() and not output.is_symlink() and not output.with_suffix('.wxs').exists() and
            not output.with_suffix('.wxs').is_symlink(), 'MSI展開先/定義は新規pathが必要です')
    run([wix / 'dark.exe', '-nologo', '-x', output, '-o', output.with_suffix('.wxs'), msi])


def check_payload(extracted, files):
    actual = image_packager.inventory(extracted / 'File')
    expected = sorted([{**row, 'path': identifier('F', row['path'])} for row in files], key=lambda row: row['path'])
    require(actual == expected, 'MSI埋込payloadの全bytesが不一致です')


def build(candidate, wix, version, output):
    """新規出力にだけ候補を作る。失敗時は途中出力を保持し、完了記録を残さない。"""
    require(sys.platform == 'win32', 'MSI生成/監査にはWindowsが必要です')
    require(re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version) and
            all(int(part) <= limit for part, limit in zip(version.split('.'), (255, 255, 65535))), 'MSI候補は数値major.minor.patchの版が必要です')
    for name in ('candle.exe', 'light.exe', 'dark.exe'): require((wix / name).is_file(), '導入済みWiX 3のbinが必要です')
    require(not output.exists() and not output.is_symlink(), '既存候補は上書きしません')
    require(not output.resolve().is_relative_to(candidate.resolve()) and not output.resolve().is_relative_to(wix.resolve()), '入力内へ出力できません')
    image, record = input_image(candidate, version)
    output.mkdir(mode=0o700)
    payload = output / 'payload'; shutil.copytree(image, payload)
    files = record['files']; require(image_packager.inventory(payload) == files, 'payloadコピーが不一致です')
    product = str(uuid.uuid4()).upper()
    source = output / 'installer.wxs'; source.write_bytes(wix_source(payload.resolve(), files, version, product))
    obj = output / 'installer.wixobj'; msi = output / 'candidate.msi'
    run([wix / 'candle.exe', '-nologo', '-arch', 'x64', '-out', obj, source])
    run([wix / 'light.exe', '-nologo', '-out', msi, obj])
    tables = msi_tables(msi); check_tables(tables, files, version, product)
    extract(wix, msi, output / 'extracted'); check_payload(output / 'extracted', files)
    require(input_image(candidate, version)[1] == record, '生成中に前段候補が変化しました')
    require(image_packager.inventory(payload) == files, '生成中にpayloadが変化しました')
    sha256 = digest(msi)
    write_json(output / 'winget.yaml', manifest(version, product, sha256))
    (output / 'SHA256SUMS').write_text(sha256 + '  candidate.msi\n', encoding='utf-8')
    evidence = {'status': 'local-unsigned-msi-and-winget-candidate-not-accepted', 'package': record['package'],
                'productCode': product, 'files': files, 'msiSha256': sha256, 'tables': tables,
                'installRoot': '%LOCALAPPDATA%\\Programs\\MCIconGenerator', 'payloadUpdateOwner': 'bootstrapper',
                'bootstrapUpdateOwner': record['package']['owner'], 'unverified': UNVERIFIED,
                'sourcePackagingSha256': digest(candidate / 'packaging.json'),
                'pendingPublication': ['PackageVersionの正式確定', 'PackageIdentifierの登録確認', 'InstallerUrl',
                                       'コード署名後のInstallerSha256/ProductCode再取得', 'InstallerType=msiの実winget受入'],
                'tools': [{'path': str(wix / name), 'sha256': digest(wix / name)} for name in ('candle.exe', 'light.exe', 'dark.exe')]}
    write_json(output / 'installer-audit.json', evidence)
    return evidence


def audit(candidate, wix, scratch):
    """完了候補を再監査する。scratchは未使用pathで指定し、MSIは起動しない。"""
    image_packager.tree(candidate)
    evidence = json.loads((candidate / 'installer-audit.json').read_text(encoding='utf-8'))
    version = evidence['package']['bootstrapVersion']; files = evidence['files']
    require(evidence['status'] == 'local-unsigned-msi-and-winget-candidate-not-accepted' and evidence['unverified'] == UNVERIFIED, '候補の未検証範囲が不一致です')
    require(image_packager.inventory(candidate / 'payload') == files, '候補payload全bytesが不一致です')
    check_image(candidate / 'payload', evidence['package'])
    require((candidate / 'installer.wxs').read_bytes() == wix_source((candidate / 'payload').resolve(), files, version, evidence['productCode']), 'WiX定義が不一致です')
    msi = candidate / 'candidate.msi'; sha256 = digest(msi)
    require(sha256 == evidence['msiSha256'] and (candidate / 'SHA256SUMS').read_text(encoding='utf-8') == sha256 + '  candidate.msi\n', 'MSI checksumが不一致です')
    require(json.loads((candidate / 'winget.yaml').read_text(encoding='utf-8')) == manifest(version, evidence['productCode'], sha256), 'winget manifestが契約と不一致です')
    tables = msi_tables(msi); check_tables(tables, files, version, evidence['productCode'])
    require(tables == evidence['tables'], 'MSI tableが完了記録と不一致です')
    require(not scratch.exists() and not scratch.is_symlink(), '監査scratchは新規pathが必要です')
    require(not scratch.resolve().is_relative_to(candidate.resolve()), '候補内へ監査scratchを作れません')
    extract(wix, msi, scratch); check_payload(scratch, files)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build_parser = commands.add_parser('build')
    for name in ('candidate', 'wix', 'output'): build_parser.add_argument('--' + name, type=Path, required=True)
    build_parser.add_argument('--version', required=True)
    audit_parser = commands.add_parser('audit')
    for name in ('candidate', 'wix', 'scratch'): audit_parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'build': build(args.candidate.resolve(), args.wix.resolve(), args.version, args.output.absolute())
    else: audit(args.candidate.resolve(), args.wix.resolve(), args.scratch.absolute())


if __name__ == '__main__': main()
