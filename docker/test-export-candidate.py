"""renderer輸送正規化がblob本文と構造を保持することを検査する。"""
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('export_candidate', Path(__file__).with_name('export-candidate.py'))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class NormalizeTest(unittest.TestCase):
    def test_order_owner_time_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outputs = []
            for index, order in enumerate([['layer', 'config'], ['config', 'layer']]):
                source, target = root / f'{index}.tar', root / f'{index}-normalized.tar'
                with tarfile.open(source, 'w') as archive:
                    for name in order:
                        body = (name + '\n').encode()
                        member = tarfile.TarInfo(name)
                        member.size = len(body)
                        member.mode = 0o640
                        member.uid = 1000 + index
                        member.gid = 1000 + index
                        member.mtime = index + 1
                        member.uname = member.gname = 'builder'
                        archive.addfile(member, io.BytesIO(body))
                exporter.normalize(source, target)
                outputs.append(target.read_bytes())
                with tarfile.open(target) as archive:
                    self.assertEqual(archive.getnames(), ['config', 'layer'])
                    for member in archive:
                        self.assertEqual(archive.extractfile(member).read(), (member.name + '\n').encode())
                        self.assertEqual(member.mode, 0o640)
                        self.assertEqual((member.uid, member.gid, member.mtime), (0, 0, exporter.EPOCH))
            self.assertEqual(*outputs)

    def test_duplicate_and_link_are_rejected(self):
        for link in [False, True]:
            with self.subTest(link=link), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with tarfile.open(root / 'source.tar', 'w') as archive:
                    member = tarfile.TarInfo('blob')
                    if link:
                        member.type = tarfile.SYMTYPE
                        member.linkname = 'outside'
                    archive.addfile(member)
                    if not link:
                        archive.addfile(member)
                with self.assertRaises(ValueError):
                    exporter.normalize(root / 'source.tar', root / 'target.tar')


if __name__ == '__main__':
    unittest.main()
