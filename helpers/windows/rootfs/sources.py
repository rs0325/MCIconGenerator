"""測定済みrootfs候補へB2の全package source対応表を結合する。"""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="build.pyの測定名")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--offline", action="store_true", help="取得済みSources索引だけを使用する")
    args = parser.parse_args()
    measured = ROOT / "build/windows-rootfs" / args.name
    summary = json.loads((measured / "summary.json").read_bytes())
    source = ROOT / "build/windows-rootfs/sources"
    evidence = source / args.name
    subprocess.run([sys.executable, str(ROOT / "docker/inspect-sources.py"),
                    "--image", summary["image_id"], "--expected-image", summary["image_id"],
                    "--output", str(evidence)], check=True, stdout=subprocess.DEVNULL)
    spec = importlib.util.spec_from_file_location("mapping", ROOT / "docker/map-sources.py")
    mapping = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mapping)
    table = mapping.generate(evidence / "image-evidence.json", measured / "packages.tsv",
                             source / "indexes", offline=args.offline, refs=(91, 95))
    table["role"] = "guest-base"
    table["rootfs"] = summary["inventory"]["guestBase"]
    paths = ["helpers/windows/src/main/guest/guestbridge.py",
             "helpers/macos/src/main/native/cpu-probe.S", "LICENSE"]
    project = []
    for name in paths:
        raw = (ROOT / name).read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        if digest != summary["inputs_sha256"][name]:
            raise ValueError("測定後に独自ソースが変更されています: " + name)
        project.append({"path": name, "bytes": len(raw), "sha256": digest})
    table["project"] = {"license": summary["inventory"]["projectLicense"],
                        "sources": project,
                        "probeToolchain": {"path": "helpers/windows/rootfs/probe-packages.lock",
                                           "sha256": summary["inputs_sha256"]["helpers/windows/rootfs/probe-packages.lock"]},
                        "sourceArchivePublished": False}
    raw = (json.dumps(table, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    args.output.write_bytes(raw)
    args.output.with_suffix(args.output.suffix + ".sha256").write_text(
        hashlib.sha256(raw).hexdigest() + "  " + args.output.name + "\n", encoding="utf-8")
    print(json.dumps(table["summary"], ensure_ascii=False))
    return int(table["summary"]["unmatched"] != 0)


if __name__ == "__main__":
    raise SystemExit(main())
