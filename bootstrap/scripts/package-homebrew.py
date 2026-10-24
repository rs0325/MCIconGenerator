"""Java同梱Mac app-imageをHomebrew用archiveと明示配布先のFormula候補に包装する。"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import re
import stat
import tarfile
from urllib.parse import urlsplit

LAUNCHER = b'''#!/bin/sh
script=$0
while [ -L "$script" ]; do
    directory=$(CDPATH= cd -P -- "$(dirname -- "$script")" && pwd) || exit 1
    target=$(readlink "$script") || exit 1
    case $target in
        /*) script=$target ;;
        *) script=$directory/$target ;;
    esac
done
prefix=$(CDPATH= cd -P -- "$(dirname -- "$script")/.." && pwd) || exit 1
exec "$prefix/libexec/MCIconBootstrap.app/Contents/MacOS/MCIconBootstrap" "$@"
'''


def archive(image, output):
    """homebrew所有appだけを包装し、file hash・modeとarchive hashを隣接JSONへ記録する。"""
    audit = output.with_suffix('.json')
    if output == audit:
        raise ValueError('archiveと監査記録の出力先を分けてください')
    if output.exists() or output.is_symlink() or audit.exists() or audit.is_symlink():
        raise ValueError('既存archiveまたは監査記録は上書きしません')
    if image.name != 'MCIconBootstrap.app' or image.is_symlink():
        raise ValueError('MCIconBootstrap.appの実ディレクトリを指定してください')
    files = sorted(image.rglob('*'))
    if any(file.is_symlink() or not (file.is_file() or file.is_dir()) for file in files):
        raise ValueError('app-imageのlinkや特殊fileは包装しません')
    config = image / 'Contents/app/bootstrap-package.json'
    package = json.loads(config.read_text())
    if package['owner'] != 'homebrew' or package['host']['os'] != 'macos':
        raise ValueError('homebrew所有のMac app-imageが必要です')
    version = package['bootstrapVersion']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', version):
        raise ValueError('bootstrapVersionの形式が不正です')
    arch = package['host']['arch']
    if arch != 'aarch64':
        raise ValueError('初版Homebrew候補の対象はApple Siliconのみです')
    for required in ('Contents/MacOS/MCIconBootstrap', 'Contents/runtime/Contents/Home/bin/java'):
        if not (image / required).is_file() or not (image / required).stat().st_mode & stat.S_IXUSR:
            raise ValueError('実行可能な同梱launcherとJavaが必要です')
    root = f'mcicon-bootstrap-{version}-macos-{arch}'
    records = [{'path': 'bin/mcicon', 'bytes': len(LAUNCHER), 'sha256': hashlib.sha256(LAUNCHER).hexdigest(), 'mode': '755'}]
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as stream, tarfile.open(fileobj=stream, mode='w:gz', dereference=False) as bundle:
        launcher = tarfile.TarInfo(root + '/bin/mcicon')
        launcher.size = len(LAUNCHER)
        launcher.mode = 0o755
        bundle.addfile(launcher, io.BytesIO(LAUNCHER))
        for file in files:
            relative = 'libexec/MCIconBootstrap.app/' + file.relative_to(image).as_posix()
            bundle.add(file, arcname=root + '/' + relative, recursive=False)
            if file.is_file():
                records.append({'path': relative, 'bytes': file.stat().st_size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'mode': format(stat.S_IMODE(file.stat().st_mode), 'o')})
    evidence = {'status': 'local-homebrew-candidate-not-installed-or-published', 'minimumMacOS': '15.7.7', 'package': package, 'archive': output.name,
                'bytes': output.stat().st_size, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'files': records}
    with audit.open('x') as stream:
        stream.write(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')


def ruby_string(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"


def formula(bundle, url, sha256, output):
    """明示HTTPS URLとarchiveの実hash一致を確認し、単一archのFormula候補を生成する。"""
    location = urlsplit(url)
    if location.scheme != 'https' or not location.netloc or location.username or location.password or location.fragment or any(ord(c) < 32 for c in url):
        raise ValueError('認証情報やfragmentを含まない明示HTTPS URLが必要です')
    if not re.fullmatch('[0-9a-f]{64}', sha256) or hashlib.sha256(bundle.read_bytes()).hexdigest() != sha256:
        raise ValueError('明示hashとarchiveの実bytesが一致しません')
    evidence = json.loads(bundle.with_suffix('.json').read_text())
    if evidence['sha256'] != sha256 or evidence['package']['owner'] != 'homebrew':
        raise ValueError('Homebrew archiveの監査記録と一致しません')
    package = evidence['package']
    if package['host'] != {'os': 'macos', 'arch': 'aarch64'}:
        raise ValueError('初版Formula候補の対象はMac Apple Siliconのみです')
    text = f'''class MciconBootstrap < Formula
  desc "Minecraftアイコン生成CLIのJava同梱bootstrap"
  homepage "https://github.com/rs0325/MCIconGenerator"
  url {ruby_string(url)}
  version {ruby_string(package['bootstrapVersion'])}
  sha256 {ruby_string(sha256)}
  license "MIT"

  depends_on macos: :sequoia
  depends_on arch: :arm64

  def install
    macos_version = IO.popen(["/usr/bin/sw_vers", "-productVersion"], &:read).strip
    odie "macOS 15.7.7以降が必要です" if Version.new(macos_version) < Version.new("15.7.7")
    libexec.install "libexec/MCIconBootstrap.app"
    bin.install "bin/mcicon"
  end

  def caveats
    <<~EOS
      brew表示版はbootstrapの版です。実行payload版はmcicon --versionで確認できます。
      通常uninstallではユーザー領域のtool slot、runtime cache、生成PNGを保持します。
    EOS
  end

  test do
    system bin/"mcicon", "--version"
  end
end
'''
    with output.open('x') as stream:
        stream.write(text)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    packing = commands.add_parser('archive')
    packing.add_argument('--app-image', type=Path, required=True)
    packing.add_argument('--output', type=Path, required=True)
    generating = commands.add_parser('formula')
    generating.add_argument('--archive', type=Path, required=True)
    generating.add_argument('--url', required=True)
    generating.add_argument('--sha256', required=True)
    generating.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'archive':
        archive(args.app_image, args.output)
    else:
        formula(args.archive, args.url, args.sha256, args.output)
