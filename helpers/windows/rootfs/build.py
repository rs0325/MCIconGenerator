"""Windows prepared rootfs候補を生成し、固定入力と測定結果を保存する。"""

import argparse
import copy
import hashlib
import io
import json
import lzma
from pathlib import Path
import subprocess
import tarfile
import time

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
EPOCH = 1793664000
MEMORY_LIMIT = 64 * 1024 * 1024
TRANSPORT_FILES = {
    "etc/hostname": b"mcicon-guest\n",
    "etc/hosts": b"127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
    "etc/resolv.conf": b"",
}


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def sha_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize(source, target):
    """Docker輸送由来の3設定を固定し、数値所有者とlink・特殊fileを保持する。"""
    with tarfile.open(source) as before, tarfile.open(target, "w", format=tarfile.PAX_FORMAT) as after:
        for old in sorted(before.getmembers(), key=lambda member: member.name):
            if old.name == ".dockerenv":
                continue
            member = copy.copy(old)
            member.mtime = EPOCH
            member.uname = member.gname = ""
            member.pax_headers = {k: v for k, v in old.pax_headers.items()
                                  if k not in ("mtime", "atime", "ctime")}
            if member.name in TRANSPORT_FILES:
                body = TRANSPORT_FILES[member.name]
                member.type = tarfile.REGTYPE
                member.mode, member.uid, member.gid = 0o644, 0, 0
                member.linkname, member.size = "", len(body)
                after.addfile(member, io.BytesIO(body))
            else:
                after.addfile(member, before.extractfile(old) if old.isfile() else None)


def members(path):
    with tarfile.open(path) as archive:
        return [{"path": m.name, "type": m.type.decode("ascii"), "mode": m.mode,
                 "uid": m.uid, "gid": m.gid, "mtime": m.mtime, "size": m.size,
                 "link": m.linkname, "pax": m.pax_headers,
                 "sha256": hashlib.file_digest(archive.extractfile(m), "sha256").hexdigest()
                 if m.isfile() else None} for m in archive]


def differences(before, after):
    left = {r["path"]: r for r in before}
    right = {r["path"]: r for r in after}
    return [{"path": path, "changes": {
        key: [left.get(path, {}).get(key), right.get(path, {}).get(key)]
        for key in sorted(left.get(path, {}).keys() | right.get(path, {}).keys())
        if left.get(path, {}).get(key) != right.get(path, {}).get(key)}}
        for path in sorted(left.keys() | right.keys()) if left.get(path) != right.get(path)]


def compress(source, target):
    with source.open("rb") as before, lzma.open(target, "wb", preset=6, check=lzma.CHECK_CRC64) as after:
        while chunk := before.read(1024 * 1024):
            after.write(chunk)
    decoder = lzma.LZMADecompressor(memlimit=MEMORY_LIMIT)
    digest = hashlib.sha256()
    with target.open("rb") as stream:
        while chunk := stream.read(65536):
            digest.update(decoder.decompress(chunk))
    if not decoder.eof or decoder.unused_data or digest.hexdigest() != sha_file(source):
        raise ValueError("XZの64MiB制限付き展開照合に失敗しました")


def measure(name):
    output = ROOT / "build/windows-rootfs" / name
    output.mkdir(parents=True, exist_ok=False)
    tag = "mcicon-windows-rootfs-c1:" + name
    inputs = {p.relative_to(ROOT).as_posix(): sha_file(p) for p in [
        HERE / "Dockerfile", HERE / "packages.lock", HERE / "probe-packages.lock",
        HERE / "build.py", ROOT / "LICENSE", ROOT / "helpers/windows/src/main/guest/guestbridge.py",
        ROOT / "helpers/macos/src/main/native/cpu-probe.S"]}
    args = ["docker", "buildx", "build", "--platform", "linux/amd64", "--no-cache",
            "--provenance=false", "--progress=plain", "--build-arg", f"SOURCE_DATE_EPOCH={EPOCH}",
            "--output", "type=docker,rewrite-timestamp=true",
            "-f", str(HERE / "Dockerfile"), "-t", tag, str(ROOT)]
    started = time.monotonic()
    with (output / "build.log").open("wb") as log:
        run(args, stdout=log, stderr=subprocess.STDOUT)
    build_seconds = time.monotonic() - started
    info = json.loads(run(["docker", "image", "inspect", tag], capture_output=True).stdout)[0]
    write_json(output / "image.json", info)
    packages = run(["docker", "run", "--rm", "--network=none", "--entrypoint", "dpkg-query",
                    tag, "-W", "-f=${binary:Package}\t${Version}\n"], capture_output=True).stdout
    (output / "packages.tsv").write_bytes(packages)
    container = run(["docker", "create", "--network=none", "--entrypoint", "/bin/true", tag],
                    capture_output=True, text=True).stdout.strip()
    started = time.monotonic()
    try:
        run(["docker", "export", "-o", str(output / "docker-export.tar"), container])
    finally:
        run(["docker", "rm", container], stdout=subprocess.DEVNULL)
    raw_members = members(output / "docker-export.tar")
    write_json(output / "export-members.json", raw_members)
    normalize(output / "docker-export.tar", output / "rootfs.tar")
    listing = members(output / "rootfs.tar")
    write_json(output / "files.json", listing)
    compress(output / "rootfs.tar", output / "rootfs.tar.xz")
    xz = run(["docker", "run", "--rm", "--network=none", "--read-only",
              "--mount", f"type=bind,source={output},target=/measure,readonly",
              "--entrypoint", "/usr/bin/xz", tag,
              "--robot", "--list", "-vv", "/measure/rootfs.tar.xz"], capture_output=True).stdout
    (output / "xz-robot.txt").write_bytes(xz)
    decoder_memory = int(next(line.split("\t")[1] for line in xz.decode().splitlines()
                              if line.startswith("summary\t")))
    if decoder_memory > MEMORY_LIMIT:
        raise ValueError("xz実測のdecoderメモリが64MiBを超えています")
    smoke = r"""set -eu
/usr/bin/python3 -c 'import fcntl, hashlib, json, os, pathlib, signal, socket, subprocess, tarfile, uuid; compile(pathlib.Path("/usr/lib/mcicon/guestbridge.py").read_bytes(), "guestbridge.py", "exec")'
/usr/lib/mcicon/cpu-probe
/usr/bin/docker --version
/usr/bin/dockerd --version
/usr/bin/containerd --version
/usr/bin/runc --version
find /etc/apt/apt.conf.d /etc/cron.daily /etc/systemd /usr/lib/systemd -iname '*apt*' -o -iname '*unattended*'
"""
    smoke_result = run(["docker", "run", "--rm", "--network=none", "--read-only",
                        "--entrypoint", "/bin/sh", tag, "-c", smoke],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    loader = """import subprocess
for name in ("python3", "docker", "dockerd", "containerd", "runc"):
    result = subprocess.run(["ldd", "/usr/bin/" + name], capture_output=True, text=True)
    text = result.stdout + result.stderr
    print(name + ": " + text)
    if "not found" in text or (result.returncode and "not a dynamic executable" not in text):
        raise SystemExit(1)
"""
    dependencies = run(["docker", "run", "--rm", "--network=none", "--read-only",
                        "--entrypoint", "/usr/bin/python3", tag, "-c", loader],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (output / "smoke.txt").write_bytes(smoke_result.stdout + dependencies.stdout)
    by_path = {m["path"]: m for m in listing}
    inventory = {
        "schemaVersion": 1, "guestBridgeAbi": 1,
        "projectLicense": {"path": "/usr/share/doc/mcicon/copyright", "license": "MIT",
                           "sha256": by_path["usr/share/doc/mcicon/copyright"]["sha256"]},
        "guestBase": {"format": "tar.xz", "bytes": (output / "rootfs.tar.xz").stat().st_size,
                      "sha256": sha_file(output / "rootfs.tar.xz"),
                      "bridgeSha256": by_path["usr/lib/mcicon/guestbridge.py"]["sha256"],
                      "cpuProbeSha256": by_path["usr/lib/mcicon/cpu-probe"]["sha256"],
                      "engines": {"/usr/bin/" + n: by_path["usr/bin/" + n]["sha256"]
                                  for n in ("docker", "dockerd", "containerd", "runc")}}}
    if inventory["guestBase"]["bridgeSha256"] != inputs["helpers/windows/src/main/guest/guestbridge.py"]:
        raise ValueError("guestbridgeのexact bytesが一致しません")
    write_json(output / "inventory.json", inventory)
    automatic = [m for m in listing if
                 (m["path"].startswith(("etc/apt/", "etc/cron", "etc/systemd/", "usr/lib/systemd/"))
                  and ("apt" in m["path"].rsplit("/", 1)[-1] or "unattended" in m["path"]))]
    summary = {"tag": tag, "image_id": info["Id"], "image_bytes": info["Size"],
               "build_seconds": round(build_seconds, 3),
               "export_measure_seconds": round(time.monotonic() - started, 3),
               "inputs_sha256": inputs, "source_date_epoch": EPOCH,
               "docker_export_sha256": sha_file(output / "docker-export.tar"),
               "tar_sha256": sha_file(output / "rootfs.tar"),
               "tar_bytes": (output / "rootfs.tar").stat().st_size,
               "tar_xz_sha256": sha_file(output / "rootfs.tar.xz"),
               "tar_xz_bytes": (output / "rootfs.tar.xz").stat().st_size,
               "expanded_regular_bytes": sum(m["size"] for m in listing if m["type"] == "0"),
               "files_sha256": sha_file(output / "files.json"),
               "packages_sha256": sha_file(output / "packages.tsv"), "members": len(listing),
               "xz": {"preset": 6, "check": "CRC64", "decoder_limit_bytes": MEMORY_LIMIT, "decoder_memory_bytes": decoder_memory,
                      "bounded_decode_matches_tar": True, "robot": xz.decode()},
               "automatic_apt_files": automatic, "inventory": inventory}
    write_json(output / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False), flush=True)


def compare(left, right, output):
    root = ROOT / "build/windows-rootfs"
    a = json.loads((root / left / "summary.json").read_bytes())
    b = json.loads((root / right / "summary.json").read_bytes())
    fields = ("inputs_sha256", "image_id", "docker_export_sha256", "tar_sha256",
              "tar_xz_sha256", "files_sha256", "packages_sha256")
    raw = differences(json.loads((root / left / "export-members.json").read_bytes()),
                      json.loads((root / right / "export-members.json").read_bytes()))
    normalized = differences(json.loads((root / left / "files.json").read_bytes()),
                             json.loads((root / right / "files.json").read_bytes()))
    result = {"schemaVersion": 1, "date": "2026-11-04", "refs": [91, 95],
              "runs": [a, b], "equal": {key: a[key] == b[key] for key in fields},
              "raw_export_differences": raw, "normalized_differences": normalized,
              "difference_classes": {
                  scope: {
                      kind: [d["path"] for d in entries if
                             ("docker_transport" if d["path"] in TRANSPORT_FILES or d["path"] == ".dockerenv"
                              else "metadata_only" if set(d["changes"]) <= {"mtime", "pax"}
                              else "content_or_structure") == kind]
                      for kind in ("docker_transport", "metadata_only", "content_or_structure")}
                  for scope, entries in (("raw", raw), ("normalized", normalized))},
              "unverified": ["WSL import", "実guest起動", "offline製品経路", "cgroup/容量能力", "Docker engine実起動"],
              "normalization": {"mtime": EPOCH, "owner": "数値uid/gid保持、uname/gnameを空にする",
                                "order": "path昇順", "removed": [".dockerenv"],
                                "transport_files": {k: v.decode() for k, v in TRANSPORT_FILES.items()}}}
    output.parent.mkdir(parents=True, exist_ok=True)
    write_json(output, result)
    print(json.dumps({"equal": result["equal"], "raw_differences": len(raw),
                      "normalized_differences": len(normalized)}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", nargs="?")
    parser.add_argument("--compare", nargs=2)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.compare and args.output:
        compare(*args.compare, args.output)
    elif args.name and not args.compare:
        measure(args.name)
    else:
        parser.error("測定名、または--compare 2件と--outputが必要です")
