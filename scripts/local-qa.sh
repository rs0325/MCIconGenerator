#!/bin/sh
# Explicit local integration run; downloads stay in the ignored cache.
set -eu
qa_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$qa_root"
qa_cache=${MCICON_CACHE:-"$qa_root/.local/qa-cache"}
qa_output=${MCICON_QA_OUTPUT:-"$qa_root/.local/qa-output"}
qa_cli="$qa_root/cli/build/install/mcicon/bin/mcicon"
./gradlew test :cli:installDist
mkdir -p "$qa_output/reports"
for qa_version in 1.13 1.13.1 1.13.2 1.21.4 26.3; do
    "$qa_cli" render --version "$qa_version" --all --allow-experimental \
        --cache "$qa_cache" --out "$qa_output" --json >"$qa_output/reports/$qa_version-result.json"
    python3 scripts/audit-export.py "$qa_output/java/$qa_version/icons/manifest.json" \
        --report "$qa_output/reports/$qa_version-audit.json"
done
"$qa_cli" extract --version 1.21.4 --all --cache "$qa_cache" --out "$qa_output" --json \
    >"$qa_output/reports/raw-result.json"
python3 scripts/audit-export.py "$qa_output/java/1.21.4/raw/manifest.json" \
    --source-jar "$qa_cache/versions/1.21.4/client.jar" --report "$qa_output/reports/raw-audit.json"
"$qa_cli" cache verify --cache "$qa_cache" --json >"$qa_output/reports/cache.json"
