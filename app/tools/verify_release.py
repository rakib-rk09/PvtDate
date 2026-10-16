#!/usr/bin/env python3
"""Verify a built PvtDate release APK.

Checks (using only Python + the APK itself):
  * package name / version
  * required + absent permissions
  * zip alignment of STORED entries
  * presence of classes.dex and assets/index.html
  * that the bundled index.html matches the repository source

Exit code 0 = all checks passed.
"""
import os
import struct
import sys
import zipfile

EXPECT_PKG = "com.pvtdate.app"
EXPECT_VERSION = "1.1.1"
EXPECT_PERMS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
}
FORBIDDEN_PERMS = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.CAMERA",
    "android.permission.READ_CONTACTS",
    "android.permission.RECORD_AUDIO",
}

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  [ok] " if ok else "  [FAIL] ") + name + ((" — " + detail) if detail else ""))


def read_manifest_strings(apk):
    # Minimal AXML string-pool scan: binary AndroidManifest.xml keeps all
    # strings in a UTF-16 string pool, so substring matching is reliable enough
    # for attribute/permission checks.
    with zipfile.ZipFile(apk) as z:
        data = z.read("AndroidManifest.xml")
    # Decode UTF-16LE heuristically and also ASCII; return a bag of strings.
    text = data.decode("utf-16-le", "ignore")
    return text


def aligned(path, alignment=4):
    with open(path, "rb") as fh:
        data = fh.read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        return False, ["not a zip"]
    central = struct.unpack_from("<I", data, eocd + 16)[0]
    total = struct.unpack_from("<H", data, eocd + 10)[0]
    pos = central
    bad = []
    for _ in range(total):
        if data[pos:pos + 4] != b"PK\x01\x02":
            break
        method = struct.unpack_from("<H", data, pos + 10)[0]
        nl = struct.unpack_from("<H", data, pos + 28)[0]
        el = struct.unpack_from("<H", data, pos + 30)[0]
        cl = struct.unpack_from("<H", data, pos + 32)[0]
        lo = struct.unpack_from("<I", data, pos + 42)[0]
        name = data[pos + 46:pos + 46 + nl].decode("utf-8", "replace")
        if method == 0:
            lnl = struct.unpack_from("<H", data, lo + 26)[0]
            lel = struct.unpack_from("<H", data, lo + 28)[0]
            off = lo + 30 + lnl + lel
            if off % alignment:
                bad.append(name)
        pos += 46 + nl + el + cl
    return (len(bad) == 0), bad


def main():
    if len(sys.argv) < 2:
        print("usage: verify_release.py <apk> [repo-index.html]")
        return 2
    apk = sys.argv[1]
    src_index = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "index.html")

    print("Verifying " + apk)
    if not os.path.isfile(apk):
        print("  [FAIL] file not found")
        return 1

    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        bad_zip = z.testzip()
        manifest = read_manifest_strings(apk)
        embedded = z.read("assets/index.html") if "assets/index.html" in names else None

    check("zip integrity", bad_zip is None, bad_zip or "")
    check("package id present", EXPECT_PKG in manifest, EXPECT_PKG)
    check("version present", EXPECT_VERSION in manifest, EXPECT_VERSION)
    check("classes.dex present", "classes.dex" in names)
    check("assets/index.html present", "assets/index.html" in names)
    check("resources.arsc present", "resources.arsc" in names)
    check("no bundled self-apk", not any(n.startswith("assets/apk/") for n in names))

    for p in sorted(EXPECT_PERMS):
        check("permission " + p.split(".")[-1], p.split(".")[-1] in manifest)
    for p in sorted(FORBIDDEN_PERMS):
        check("no " + p.split(".")[-1], p.split(".")[-1] not in manifest)

    ok_align, bad = aligned(apk)
    check("zipalign (STORED 4-byte)", ok_align, (", ".join(bad) if bad else "all aligned"))

    if embedded is not None and os.path.isfile(src_index):
        with open(src_index, "rb") as fh:
            same = fh.read() == embedded
        check("bundled index.html matches source", same)

    failed = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
