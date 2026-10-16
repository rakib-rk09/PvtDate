#!/usr/bin/env python3
"""Check that STORED (uncompressed) entries in an APK zip are 4-byte aligned.

Android performance guidance (and Play) expects uncompressed entries such as
resources.arsc to be aligned so they can be memory-mapped. The build appends
classes.dex with `zip`, which leaves aapt2's existing alignment untouched; this
script verifies that no STORED entry is misaligned and reports the result.
"""
import struct
import sys


def check(path, alignment=4):
    with open(path, "rb") as fh:
        data = fh.read()

    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        print("not a zip file")
        return 2

    central_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    total = struct.unpack_from("<H", data, eocd + 10)[0]

    pos = central_offset
    stored = 0
    bad = []
    for _ in range(total):
        if data[pos:pos + 4] != b"PK\x01\x02":
            break
        method = struct.unpack_from("<H", data, pos + 10)[0]
        name_len = struct.unpack_from("<H", data, pos + 28)[0]
        extra_len = struct.unpack_from("<H", data, pos + 30)[0]
        comment_len = struct.unpack_from("<H", data, pos + 32)[0]
        local_off = struct.unpack_from("<I", data, pos + 42)[0]
        name = data[pos + 46:pos + 46 + name_len].decode("utf-8", "replace")

        if method == 0:
            lname = struct.unpack_from("<H", data, local_off + 26)[0]
            lextra = struct.unpack_from("<H", data, local_off + 28)[0]
            data_off = local_off + 30 + lname + lextra
            stored += 1
            if data_off % alignment != 0:
                bad.append((name, data_off, data_off % alignment))

        pos += 46 + name_len + extra_len + comment_len

    print("STORED entries: %d" % stored)
    if bad:
        print("MISALIGNED (%d):" % len(bad))
        for name, off, rem in bad:
            print("  %-40s offset=%d mod4=%d" % (name, off, rem))
        return 1
    print("ALIGNMENT OK: all STORED entries are %d-byte aligned" % alignment)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: check_align.py <apk>")
        sys.exit(2)
    sys.exit(check(sys.argv[1]))
