"""rendererのビルド結果を保存し、独立した2回の結果を比較する。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import time

ROOT = Path(__file__).resolve().parents[1]


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def sha_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def archive_members(archive):
    with tarfile.open(archive) as tar:
        return [{"path": item.name, "type": item.type.decode("ascii"), "mode": item.mode,
                 "uid": item.uid, "gid": item.gid, "mtime": item.mtime, "size": item.size,
                 "sha256": hashlib.file_digest(tar.extractfile(item), "sha256").hexdigest()
                 if item.isfile() else None} for item in tar]


def archives(output, tag, image_id):
    summary = {}
    members = {}
    archive = output / "image.tar"
    try:
        for prefix, ref in [("archive", tag), ("id_archive", image_id)]:
            run(["docker", "save", "-o", str(archive), ref])
            summary[prefix + "_bytes"] = archive.stat().st_size
            summary[prefix + "_sha256"] = sha_file(archive)
            members[prefix] = archive_members(archive)
            with tarfile.open(archive) as tar:
                manifest = json.load(tar.extractfile("manifest.json"))
                config = manifest[0]["Config"]
                summary["config_digest"] = "sha256:" + hashlib.sha256(tar.extractfile(config).read()).hexdigest()
    finally:
        archive.unlink(missing_ok=True)
    (output / "archive-members.json").write_text(json.dumps(members, indent=2) + "\n", encoding="utf-8")
    return summary


def compare(left, right):
    root = ROOT / "build" / "renderer-repro"
    a = json.loads((root / left / "summary.json").read_text(encoding="utf-8"))
    b = json.loads((root / right / "summary.json").read_text(encoding="utf-8"))
    fields = ["image_id", "config_digest", "archive_sha256", "id_archive_sha256",
              "packages_sha256", "files_sha256"]
    result = {"left": left, "right": right, "equal": {key: a[key] == b[key] for key in fields}}
    def files(name):
        with (root / name / "files.jsonl").open(encoding="utf-8") as stream:
            return {record["path"]: record for record in map(json.loads, stream)}
    before, after = files(left), files(right)
    result["file_differences"] = [
        {"path": path, "changes": {key: [before.get(path, {}).get(key), after.get(path, {}).get(key)]
         for key in sorted(before.get(path, {}).keys() | after.get(path, {}).keys())
         if before.get(path, {}).get(key) != after.get(path, {}).get(key)}}
        for path in sorted(before.keys() | after.keys()) if before.get(path) != after.get(path)]
    target = root / (left + "-vs-" + right + ".json")
    target.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"equal": result["equal"], "changed_files": len(result["file_differences"]),
                      "record": str(target)}))


def smoke(output, tag):
    command = """set -eu
glxinfo -B > /tmp/repro-glx.txt
cat /tmp/repro-glx.txt
grep -q llvmpipe /tmp/repro-glx.txt
grep -qw sse4_1 /proc/cpuinfo
test "$LIBGL_ALWAYS_SOFTWARE" = 1
test "$LP_NATIVE_VECTOR_WIDTH" = 128
test "$GALLIUM_OVERRIDE_CPU_CAPS" = sse4.1
test "$ALSOFT_DRIVERS" = null
printf 'CPU=sse4.1; vector=128; OpenAL=null\\n'
for java in /opt/java/java8/bin/java /opt/java/java17/bin/java /opt/java/java21/bin/java /opt/java/openjdk/bin/java; do
    readlink -f "$java"
    "$java" -version
    sha256sum "$java"
done
test "$(command -v java)" = /opt/java/openjdk/bin/java
"""
    result = subprocess.run(["docker", "run", "--rm", "--network=none", tag, "/bin/sh", "-c", command],
                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (output / "smoke.txt").write_bytes(result.stdout)
    result.check_returncode()


def measure(name, fixed, dockerfile):
    output = ROOT / "build" / "renderer-repro" / name
    output.mkdir(parents=True, exist_ok=False)
    tag = "mcicon-renderer-repro:" + name
    args = ["docker", "buildx", "build", "--platform", "linux/amd64",
            "--no-cache", "--provenance=false", "--progress=plain",
            "-f", str(dockerfile), "-t", tag]
    if fixed:
        args += ["--build-arg", "SOURCE_DATE_EPOCH=1793664000",
                 "--output", "type=docker,rewrite-timestamp=true"]
    else:
        args += ["--load"]
    args += [str(ROOT)]
    inputs = {path.relative_to(ROOT).as_posix(): sha_file(path)
              for path in [dockerfile, ROOT / "docker/entrypoint.sh", ROOT / "docker/packages.lock"]
              if path.exists()}
    started = time.monotonic()
    with (output / "build.log").open("wb") as log:
        result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT)
    elapsed = time.monotonic() - started
    if result.returncode:
        raise RuntimeError(f"ビルド失敗: {output / 'build.log'}")
    info = json.loads(run(["docker", "image", "inspect", tag], capture_output=True).stdout)[0]
    (output / "image.json").write_text(json.dumps(info, indent=2), encoding="utf-8")
    summary = {"tag": tag, "image_id": info["Id"], "image_bytes": info["Size"],
               "build_seconds": round(elapsed, 3)}
    summary.update(archives(output, tag, info["Id"]))
    summary["inputs_sha256"] = inputs
    packages = run(["docker", "run", "--rm", "--network=none", "--entrypoint", "dpkg-query",
                    tag, "-W", "-f=${binary:Package}\t${Version}\n"], capture_output=True).stdout
    (output / "packages.tsv").write_bytes(packages)
    summary["packages_sha256"] = hashlib.sha256(packages).hexdigest()
    container = run(["docker", "create", "--network=none", "--entrypoint", "/bin/true", tag],
                    capture_output=True, text=True).stdout.strip()
    try:
        with (output / "files.jsonl").open("w", encoding="utf-8", newline="\n") as listing:
            process = subprocess.Popen(["docker", "export", container], stdout=subprocess.PIPE)
            with tarfile.open(fileobj=process.stdout, mode="r|") as tar:
                for member in tar:
                    record = {"path": member.name, "type": member.type.decode("ascii"),
                              "mode": member.mode, "uid": member.uid, "gid": member.gid,
                              "size": member.size, "mtime": member.mtime, "link": member.linkname}
                    if member.isfile():
                        record["sha256"] = hashlib.file_digest(tar.extractfile(member), "sha256").hexdigest()
                    listing.write(json.dumps(record, sort_keys=True) + "\n")
            if process.wait():
                raise RuntimeError("ファイル一覧取得失敗")
    finally:
        run(["docker", "rm", container], stdout=subprocess.DEVNULL)
    summary["files_sha256"] = sha_file(output / "files.jsonl")
    if fixed:
        smoke(output, tag)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", nargs="?", help="記録先の一意な名前")
    parser.add_argument("--fixed", action="store_true", help="時刻固定exportを使用する")
    parser.add_argument("--dockerfile", type=Path, default=ROOT / "docker/Dockerfile", help="測定するDockerfileのパス")
    parser.add_argument("--compare", nargs=2, metavar=("変更前", "変更後"), help="保存済みの測定を比較する")
    options = parser.parse_args()
    if options.compare:
        compare(*options.compare)
    elif options.name:
        measure(options.name, options.fixed, options.dockerfile.resolve())
    else:
        parser.error("測定名または--compareが必要です")
