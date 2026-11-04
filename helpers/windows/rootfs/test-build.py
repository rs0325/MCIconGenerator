"""rootfs正規化の所有者・link・差分とXZ制限をネットワークなしで検証する。"""

import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("rootfs_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class RootfsTest(unittest.TestCase):
    def archive(self, path, clock, hostname, payload=b"bridge"):
        with tarfile.open(path, "w", format=tarfile.PAX_FORMAT) as archive:
            for name, body in [("usr/lib/mcicon/guestbridge.py", payload),
                               ("etc/hostname", hostname), (".dockerenv", b"")]:
                member = tarfile.TarInfo(name)
                member.size, member.mtime = len(body), clock
                member.uid, member.gid = 42, 43
                member.uname, member.gname = "builder", "builder"
                archive.addfile(member, io.BytesIO(body))
            member = tarfile.TarInfo("usr/bin/python3")
            member.type, member.linkname, member.mtime = tarfile.SYMTYPE, "python3.12", clock
            archive.addfile(member)
            member = tarfile.TarInfo("dev/null")
            member.type, member.devmajor, member.devminor = tarfile.CHRTYPE, 1, 3
            member.mode, member.mtime = 0o666, clock
            archive.addfile(member)

    def test_normalized_bytes_and_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, clock in [("a", 1), ("b", 999)]:
                self.archive(root / (name + ".tar"), clock, name.encode())
                build.normalize(root / (name + ".tar"), root / (name + "-fixed.tar"))
            self.assertEqual((root / "a-fixed.tar").read_bytes(), (root / "b-fixed.tar").read_bytes())
            with tarfile.open(root / "a-fixed.tar") as archive:
                self.assertNotIn(".dockerenv", archive.getnames())
                bridge = archive.getmember("usr/lib/mcicon/guestbridge.py")
                self.assertEqual((bridge.uid, bridge.gid, bridge.uname, bridge.gname), (42, 43, "", ""))
                self.assertTrue(archive.getmember("usr/bin/python3").issym())
                device = archive.getmember("dev/null")
                self.assertEqual((device.devmajor, device.devminor), (1, 3))
                self.assertTrue(device.ischr())
            build.compress(root / "a-fixed.tar", root / "a.tar.xz")

    def test_content_difference_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in [("a", b"original"), ("b", b"changed")]:
                self.archive(root / (name + ".tar"), 1, b"same", content)
                build.normalize(root / (name + ".tar"), root / (name + "-fixed.tar"))
            diff = build.differences(build.members(root / "a-fixed.tar"), build.members(root / "b-fixed.tar"))
            self.assertEqual([d["path"] for d in diff], ["usr/lib/mcicon/guestbridge.py"])
            self.assertIn("sha256", diff[0]["changes"])


if __name__ == "__main__":
    unittest.main()
