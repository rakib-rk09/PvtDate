#!/usr/bin/env bash
# Run the full local test suite: web-app logic + (if built) release APK checks.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

echo "==> web app logic tests"
qjs --std app/tools/test_web.js index.html

APK="app/output/pvtdate-1.1.0-release.apk"
if [ -f "$APK" ]; then
    echo
    echo "==> release APK verification"
    python3 app/tools/verify_release.py "$APK" index.html
    echo
    echo "==> signature"
    apksigner verify -v "$APK" | sed -n '1,8p'
else
    echo
    echo "(no APK built yet — run app/build.sh to build, then re-run)"
fi
