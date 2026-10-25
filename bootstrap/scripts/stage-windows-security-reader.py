"""明示bootstrap入力へ固定reader/build/licenseを結合した新入力を作る。app-image生成/署名は別処理。"""
import argparse, json, pathlib, shutil
from security_artifact import verify, digest, require


def stage(inputs, native, output, source):
    require(inputs.is_dir() and not inputs.is_symlink() and not output.exists(), '通常input directoryと未使用outputが必要です')
    require(not output.resolve().is_relative_to(inputs.resolve()), 'input内へoutputを作れません')
    value = verify(native, source)
    for path in inputs.rglob('*'): require(not path.is_symlink() and (path.is_file() or path.is_dir()), 'inputのlink/特殊fileを拒否します')
    for name in ['windows-security-reader.exe', 'windows-security-reader.json', 'windows-security-reader-build.json', 'security-reader']:
        require(not (inputs / 'support' / name).exists(), '既readerは上書きしません')
    shutil.copytree(inputs, output)
    support = output / 'support'; support.mkdir(exist_ok=True)
    shutil.copyfile(native / 'windows-security-reader.exe', support / 'windows-security-reader.exe')
    shutil.copyfile(native / 'build.json', support / 'windows-security-reader-build.json')
    licenses = []
    for row in value['licenses']:
        target = support / 'security-reader' / row['path']; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(native / row['path'], target)
        licenses.append({'path': target.relative_to(support).as_posix(), 'bytes': row['bytes'], 'sha256': row['sha256']})
    repo = pathlib.Path(__file__).resolve().parents[2]
    for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md']:
        original = repo / name; target = support / 'security-reader/licenses/mcicon' / name
        require(original.is_file() and not original.is_symlink(), 'project license正本がありません')
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(original, target)
        licenses.append({'path': target.relative_to(support).as_posix(), 'bytes': target.stat().st_size, 'sha256': digest(target)})
    metadata = {'schemaVersion': 1, 'target': value['target'], 'sourceSha256': value['sources'][0]['sha256'], 'buildSha256': digest(native / 'build.json'),
                'executable': {k: value['artifact'][k] for k in ['bytes', 'sha256']}, 'licenses': licenses}
    with (support / 'windows-security-reader.json').open('x', encoding='utf-8') as stream: json.dump(metadata, stream, ensure_ascii=False, indent=2); stream.write('\n')
    return metadata


def main():
    parser = argparse.ArgumentParser()
    for name in ['input', 'native', 'output']: parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    source = pathlib.Path(__file__).resolve().parents[1] / 'src/main/native/windows-security-reader.cpp'
    stage(args.input, args.native, args.output, source)


if __name__ == '__main__': main()
