"""固定imageのdocker saveを正規化し、配布候補tar.gzを生成する。"""
import argparse
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import time

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location('renderer_measure', Path(__file__).with_name('measure-repro.py'))
measure = module_from_spec(spec)
spec.loader.exec_module(measure)
IMAGE = 'sha256:5493390b3cffc245416a32a855d874204dbe6710a2eca761859a0995242ab2dc'
EPOCH = 1793664000


def normalize(source, target):
    # layer/config/index本文は変更せず、外側tarの輸送metadataだけを固定する。
    with tarfile.open(source) as original, tarfile.open(target, 'w', format=tarfile.PAX_FORMAT) as output:
        entries = original.getmembers()
        names = [m.name for m in entries]
        if len(names) != len(set(names)):
            raise ValueError('save内のpath重複')
        for member in sorted(entries, key=lambda m: m.name):
            if not (member.isfile() or member.isdir()):
                raise ValueError('saveの未対応member: ' + member.name)
            member.mtime = EPOCH
            member.uid = member.gid = 0
            member.uname = member.gname = ''
            member.pax_headers = {}
            output.addfile(member, original.extractfile(member) if member.isfile() else None)


def export(image, output):
    if image != IMAGE:
        raise ValueError('B1固定image ID以外は対象外')
    info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
    if info['Id'] != IMAGE or info['Os'] != 'linux' or info['Architecture'] != 'amd64':
        raise ValueError('image/platform不一致')
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    raw = output / 'docker-save.tar'
    archive = output / 'renderer.tar'
    compressed = output / 'renderer.tar.gz'
    subprocess.run(['docker', 'save', '-o', str(raw), image], check=True, timeout=180)
    before = measure.archive_members(raw)
    normalize(raw, archive)
    after = measure.archive_members(archive)
    content = lambda rows: {r['path']: (r['type'], r['size'], r['mode'], r['sha256']) for r in rows}
    if content(before) != content(after):
        raise ValueError('正規化でmember本文/属性が変化')
    with tarfile.open(archive) as saved:
        manifest = json.load(saved.extractfile('manifest.json'))
        if len(manifest) != 1 or manifest[0]['RepoTags'] is not None:
            raise ValueError('ID指定save/tagなしを要求')
    with archive.open('rb') as source, compressed.open('xb') as destination:
        with gzip.GzipFile(filename='', mode='wb', fileobj=destination, compresslevel=6, mtime=0) as stream:
            shutil.copyfileobj(source, stream)
    result = {'imageId': image, 'platform': 'linux/amd64', 'imageBytes': info['Size'],
              'rawSave': {'bytes': raw.stat().st_size, 'sha256': measure.sha_file(raw)},
              'tar': {'bytes': archive.stat().st_size, 'sha256': measure.sha_file(archive)},
              'tarGz': {'bytes': compressed.stat().st_size, 'sha256': measure.sha_file(compressed)},
              'members': len(after), 'memberContentPreserved': True,
              'config': manifest[0]['Config'], 'seconds': round(time.monotonic() - started, 3),
              'normalization': {'epoch': EPOCH, 'uid': 0, 'gid': 0, 'ownerNames': '',
                                'order': 'path', 'gzipMtime': 0, 'gzipFilename': '', 'gzipLevel': 6}}
    (output / 'members.json').write_text(json.dumps({'raw': before, 'normalized': after}, indent=2) + '\n', encoding='utf-8')
    (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default=IMAGE)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    export(args.image, args.output.resolve())
