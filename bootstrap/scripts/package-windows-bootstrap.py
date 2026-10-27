"""明示署名seedと現在coreを検証し、Windows x64 Java同梱app-image/ZIP候補を生成する。"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import platform
import re
import shutil
import stat
import subprocess
import sys
import zipfile
from urllib.parse import urlsplit
from security_artifact import LICENSES, digest, require

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('stage_reader', Path(__file__).with_name('stage-windows-security-reader.py'))
stager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stager)


def tree(root):
    """各directoryの型を確認してから直下へ進み、link/reparse/特殊fileを拒否する。"""
    paths = []
    def visit(path):
        info = path.lstat()
        require(not stat.S_ISLNK(info.st_mode) and not getattr(info, 'st_file_attributes', 0) & 0x400, '生成入力のlink/reparseは拒否します')
        require(stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode), '生成入力の特殊fileは拒否します')
        if path != root: paths.append(path)
        if stat.S_ISDIR(info.st_mode):
            for child in sorted(path.iterdir()): visit(child)
    visit(root)
    return paths


def inventory(root):
    return [{'path': path.relative_to(root).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest(path)}
            for path in tree(root) if path.is_file()]


def check_input(inputs, expected):
    require(tree(inputs) == [inputs / row['path'] for row in expected] and inventory(inputs) == expected, 'bootstrap入力の集合/bytesが変化しました')


def run(args):
    subprocess.run([str(value) for value in args], stdin=subprocess.DEVNULL, check=True, timeout=120)


def package(jdk, inputs, seed, trust, resources, core, native, version, owner, host_os, arch, output, stable_manifest_url=None):
    """検証済み入力を新規出力だけへ包装する。installer prefixの安全性や実winget受入は保証しない。"""
    require(sys.platform == 'win32' and platform.machine().lower() in ('amd64', 'x86_64'), '生成hostはWindows x64が必要です')
    require(host_os == 'windows' and arch == 'x86_64' and owner in ('winget', 'installer', 'archive'), 'Windows x64と明示ownerが必要です')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', version), 'bootstrapVersionの形式が不正です')
    if stable_manifest_url is not None:
        url = urlsplit(stable_manifest_url)
        require(url.scheme == 'https' and url.netloc and not url.username and not url.password and not url.fragment and not any(ord(c) < 32 for c in stable_manifest_url), '明示manifest URLは認証情報の無いHTTPSが必要です')
    require(not output.exists() and not output.is_symlink(), '既存候補は上書きしません')
    for root in (jdk, inputs, seed, resources, native):
        require(root.is_dir() and not root.is_symlink(), '明示した通常input directoryが必要です')
        require(not output.resolve().is_relative_to(root.resolve()), '入力内へ出力できません')
    main = 'bootstrap-' + version + '.jar'
    jars = sorted(inputs.glob('*.jar'))
    for path in [inputs / main, trust, core, jdk / 'release', *[jdk / 'bin' / name for name in ('java.exe', 'jlink.exe', 'jpackage.exe')], *[seed / name for name in ('stable.json', 'stable.json.sig', 'tool.zip')], *jars]:
        require(path.is_file() and not path.is_symlink(), '同版JAR/seed/trust/現在core/JDKの通常fileが必要です')
    release = dict(line.split('=', 1) for line in (jdk / 'release').read_text(encoding='utf-8').splitlines() if '=' in line)
    require(release.get('JAVA_VERSION', '').startswith('"21.') and release.get('OS_ARCH') == '"x86_64"' and release.get('OS_NAME') == '"Windows"', 'Windows x64 Java21 JDKが必要です')
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        require((REPO / name).is_file(), 'project license正本がありません')
    require((REPO / 'licenses').is_dir(), 'project license一覧がありません')
    require(tree(inputs) == jars, 'bootstrap入力は直下JARだけを指定してください')
    original_jars = inventory(inputs)
    project_licenses = [path for path in tree(REPO / 'licenses') if path.is_file()]
    native_paths = [native / 'windows-security-reader.exe', native / 'build.json', *[native / 'licenses' / name for name in LICENSES]]
    for path in native_paths: require(path.is_file() and not path.is_symlink(), '固定reader/build/licenseが不足しています')
    tree(resources)
    verification_paths = [*jars, *[seed / name for name in ('stable.json', 'stable.json.sig', 'tool.zip')], trust, core, *[path for path in tree(resources) if path.is_file()], *native_paths, *project_licenses, REPO / 'LICENSE', REPO / 'THIRD_PARTY_NOTICES.md']
    verified_inputs = [{'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path)} for path in verification_paths]
    output.mkdir(mode=0o700)
    stage = output / 'staging'; stage.mkdir()
    run([jdk / 'bin/java.exe', '-Xmx512m', '-XX:-HeapDumpOnOutOfMemoryError', '-XX:-CreateCoredumpOnCrash', '-XX:-UsePerfData',
         '-cp', ';'.join(str(path) for path in jars), 'io.github.rs0325.mcicon.bootstrap.ManagedPackageVerifier', resources, seed, trust, version, core])
    check_input(inputs, original_jars)
    staged = stage / 'bootstrap-lib'
    definition = stager.stage(inputs, native, staged, REPO / 'bootstrap/src/main/native/windows-security-reader.cpp')
    check_input(inputs, original_jars)
    staged_files = inventory(staged)
    staged_records = {row['path']: row for row in staged_files}
    generated = {'support/windows-security-reader.exe', 'support/windows-security-reader-build.json', 'support/windows-security-reader.json', *['support/' + row['path'] for row in definition['licenses']]}
    allowed = {row['path'] for row in original_jars} | generated
    directories = {parent.as_posix() for name in allowed for parent in Path(name).parents if parent != Path('.')}
    require({path.relative_to(staged).as_posix() for path in tree(staged)} == allowed | directories, 'staged入力の集合が不一致です')
    expected_native = [('support/windows-security-reader.exe', definition['executable']), *[('support/' + row['path'], row) for row in definition['licenses']]]
    for name, expected_native_file in expected_native:
        require(all(staged_records[name][key] == expected_native_file[key] for key in ('bytes', 'sha256')), 'reader検証記録とstage PE/licenseのbytesが不一致です')
    require(staged_records['support/windows-security-reader-build.json']['sha256'] == definition['buildSha256'], 'reader stage build記録が不一致です')
    require(json.loads((staged / 'support/windows-security-reader.json').read_text(encoding='utf-8')) == definition, 'reader stage metadataが不一致です')
    copied_native = [(native / 'windows-security-reader.exe', 'support/windows-security-reader.exe'), (native / 'build.json', 'support/windows-security-reader-build.json'), *[(native / 'licenses' / name, 'support/security-reader/licenses/' + name) for name in LICENSES], *[(REPO / name, 'support/security-reader/licenses/mcicon/' + name) for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md')]]
    verified_records = {row['path']: row for row in verified_inputs}
    for source, target in [(path, path.name) for path in jars] + copied_native:
        original = verified_records[str(source)]; record = staged_records[target]
        require(record['bytes'] == original['bytes'] and record['sha256'] == original['sha256'], '元固定入力とstageコピーのbytesが不一致です')
    app_input = stage / 'input'; shutil.copytree(staged, app_input)
    (app_input / 'seed').mkdir()
    for name in ('stable.json', 'stable.json.sig', 'tool.zip'): shutil.copyfile(seed / name, app_input / 'seed' / name)
    shutil.copyfile(trust, app_input / 'trust.der')
    info = {'schemaVersion': 1, 'bootstrapVersion': version, 'bootstrapProtocol': 2, 'owner': owner,
            'stableManifestUrl': stable_manifest_url, 'host': {'os': host_os, 'arch': arch}}
    with (app_input / 'bootstrap-package.json').open('x', encoding='utf-8') as stream:
        json.dump(info, stream, ensure_ascii=False, indent=2); stream.write('\n')
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'): shutil.copyfile(REPO / name, app_input / name)
    shutil.copytree(REPO / 'licenses', app_input / 'licenses')
    expected = inventory(app_input)
    input_records = {row['path']: row for row in expected}
    app_paths = set(staged_records) | {'seed/stable.json', 'seed/stable.json.sig', 'seed/tool.zip', 'trust.der', 'bootstrap-package.json', 'LICENSE', 'THIRD_PARTY_NOTICES.md'} | {'licenses/' + path.relative_to(REPO / 'licenses').as_posix() for path in project_licenses}
    require(set(input_records) == app_paths, 'app入力の集合が不一致です')
    for row in staged_files:
        require(input_records[row['path']] == row, 'staged入力とapp入力のbytesが不一致です')
    verified_records = {row['path']: row for row in verified_inputs}
    copied = [(path, path.name) for path in jars] + [(seed / name, 'seed/' + name) for name in ('stable.json', 'stable.json.sig', 'tool.zip')] + [(trust, 'trust.der'), *[(REPO / name, name) for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md')], *[(path, 'licenses/' + path.relative_to(REPO / 'licenses').as_posix()) for path in project_licenses]]
    for source, target in copied:
        original = verified_records[str(source)]; record = input_records[target]
        require(record['bytes'] == original['bytes'] and record['sha256'] == original['sha256'], '検証済み入力とコピー先のbytesが不一致です')
    runtime = stage / 'runtime'
    run([jdk / 'bin/jlink.exe', '--add-modules', 'java.se,jdk.crypto.ec,jdk.security.auth', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', runtime])
    tree(runtime)
    run([jdk / 'bin/jpackage.exe', '--type', 'app-image', '--name', 'mcicon', '--win-console', '--app-version', version,
         '--input', app_input, '--main-jar', main, '--main-class', 'io.github.rs0325.mcicon.bootstrap.Main', '--runtime-image', runtime, '--dest', output / 'image'])
    image = output / 'image/mcicon'
    for name in ['mcicon.exe', 'app/mcicon.cfg', 'runtime/bin/java.exe']:
        require((image / name).is_file(), 'launcher/config/同梱Javaが不足しています')
    files = inventory(image)
    actual = {row['path']: row for row in files}
    for row in expected:
        require(actual.get('app/' + row['path']) == {**row, 'path': 'app/' + row['path']}, 'inputとapp-imageのbytesが不一致です')
    archive = output / 'candidate.zip'
    with archive.open('xb') as stream, zipfile.ZipFile(stream, mode='w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in tree(image): bundle.write(path, path.relative_to(image).as_posix())
    require(inventory(image) == files, '包装中にapp-imageが変化しました')
    with zipfile.ZipFile(archive) as bundle:
        require({row.filename for row in bundle.infolist() if not row.is_dir()} == set(actual), 'ZIP全tree一覧が不一致です')
        for row in files:
            data = bundle.read(row['path'])
            require(len(data) == row['bytes'] and hashlib.sha256(data).hexdigest() == row['sha256'], 'ZIP全tree bytesが不一致です')
    check_input(inputs, original_jars)
    require(inventory(staged) == staged_files and {path.relative_to(staged).as_posix() for path in tree(staged)} == allowed | directories, 'staged入力の集合/bytesが生成中に変化しました')
    for row in verified_inputs:
        path = Path(row['path']); require(path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], '検証対象入力が生成中に変化しました')
    audit = {'status': 'windows-app-image-and-archive-candidate-not-installer-accepted', 'package': info,
             'verifiedInputs': verified_inputs, 'bootstrapInputFiles': staged_files, 'appInputFiles': expected, 'files': files,
             'archive': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest(archive),
             'privateKeyGenerated': False, 'limits': ['正式installer prefix/配置ACL/実winget/標準非admin一般は未受入', '生成処理は新artifact起動/署名/公開を行わない']}
    with (output / 'packaging.json').open('x', encoding='utf-8') as stream:
        json.dump(audit, stream, ensure_ascii=False, indent=2); stream.write('\n')
    return audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jdk', 'input', 'seed', 'trust', 'resources', 'core', 'native', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('version', 'owner', 'os', 'arch'): parser.add_argument('--' + name, required=True)
    parser.add_argument('--stable-manifest-url')
    args = parser.parse_args()
    package(args.jdk, args.input, args.seed, args.trust, args.resources, args.core, args.native,
            args.version, args.owner, args.os, args.arch, args.output, args.stable_manifest_url)


if __name__ == '__main__': main()
