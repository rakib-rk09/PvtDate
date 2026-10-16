#!/usr/bin/env bash
#
# PvtDate — Termux-native release build.
#
# Requirements (Termux):
#   pkg install aapt2 openjdk-17 d8 apksigner zip python
#
# Signing secrets are NEVER stored in this repository. Provide them via:
#   PVTDATE_KEYSTORE       path to the signing keystore   (default: ~/.pvtdate/pvtdate.keystore)
#   PVTDATE_KEYSTORE_PASS  keystore password              (default: contents of ~/.pvtdate/keystore.pass)
#   PVTDATE_KEY_ALIAS      key alias                      (default: pvtdate)
#
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$APP_DIR/.." && pwd)"

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"

VERSION_NAME="${PVTDATE_VERSION_NAME:-1.1.1}"
VERSION_CODE="${PVTDATE_VERSION_CODE:-4}"
MIN_SDK=24
TARGET_SDK=35

KEYSTORE="${PVTDATE_KEYSTORE:-$HOME/.pvtdate/pvtdate.keystore}"
KEY_ALIAS="${PVTDATE_KEY_ALIAS:-pvtdate}"
if [ -n "${PVTDATE_KEYSTORE_PASS:-}" ]; then
    KEYSTORE_PASS="$PVTDATE_KEYSTORE_PASS"
elif [ -f "$HOME/.pvtdate/keystore.pass" ]; then
    KEYSTORE_PASS="$(cat "$HOME/.pvtdate/keystore.pass")"
else
    echo "error: no keystore password found. Set PVTDATE_KEYSTORE_PASS." >&2
    exit 1
fi

OUT="$APP_DIR/output"
BUILD="$APP_DIR/build"
APK_NAME="pvtdate-${VERSION_NAME}-release.apk"

for tool in aapt2 javac d8 apksigner zip python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "error: missing tool: $tool" >&2; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "error: missing $ANDROID_JAR" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "error: missing keystore: $KEYSTORE" >&2; exit 1; }

cd "$APP_DIR"
rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD" "$OUT" src/main/assets

echo "==> bundling web app (index.html) into assets"
cp "$ROOT_DIR/index.html" src/main/assets/index.html

echo "==> compiling resources (aapt2)"
aapt2 compile --dir src/main/res -o "$BUILD/res.zip"

echo "==> linking resources + assets (aapt2)"
aapt2 link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest src/main/AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -A src/main/assets \
    --auto-add-overlay \
    "$BUILD/res.zip"

echo "==> compiling java"
mkdir -p "$BUILD/classes"
javac -source 8 -target 8 -nowarn -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    src/main/java/com/pvtdate/app/MainActivity.java

echo "==> dexing (d8)"
rm -rf "$BUILD/dex"
mkdir -p "$BUILD/dex"
d8 --lib "$ANDROID_JAR" --min-api "$MIN_SDK" --output "$BUILD/dex" \
    "$BUILD"/classes/com/pvtdate/app/*.class

echo "==> assembling apk (append classes.dex)"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -9 "$BUILD/unsigned.apk" classes.dex )

echo "==> zipaligning"
python3 tools/zipalign.py "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> verifying zip alignment"
python3 tools/check_align.py "$BUILD/aligned.apk"

echo "==> signing (apksigner, v1+v2+v3)"
cp "$BUILD/aligned.apk" "$OUT/$APK_NAME"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$OUT/$APK_NAME" \
    "$BUILD/aligned.apk"

cp "$OUT/$APK_NAME" "$OUT/pvtdate.apk"

echo
echo "==> verifying signature"
apksigner verify --verbose --print-certs "$OUT/$APK_NAME" | sed -n '1,12p'
echo
echo "==> badging"
aapt2 dump badging "$OUT/$APK_NAME" | grep -E "^package|^launchable-activity|application-label|sdkVersion" || true
echo
echo "==> output"
ls -la "$OUT/$APK_NAME" "$OUT/pvtdate.apk"
echo
echo "Done: $OUT/$APK_NAME"
