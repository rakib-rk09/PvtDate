#!/usr/bin/env python3
"""zipalign for APKs (Termux-friendly, pure Python).

Rewrites a zip so that every STORED (uncompressed) entry starts on a 4-byte
boundary, which lets Android memory-map them (required for good performance and
by Google Play). Compressed entries are copied as-is. The result is a valid zip
that can be signed with apksigner afterwards.

Padding is injected into each entry's local extra field, which is skipped by
readers via the field length and mirrors what Android's `zipalign` does.
"""
import sys
import zipfile


def align(src, dst, alignment=4):
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)

            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system

            if info.compress_type == zipfile.ZIP_STORED:
                pos = zout.fp.tell()
                name_len = len(info.filename.encode("utf-8"))
                data_off = pos + 30 + name_len + len(info.extra)
                pad = (-data_off) % alignment
                zi.extra = info.extra + b"\x00" * pad
            else:
                zi.extra = info.extra

            zout.writestr(zi, data)


def main():
    if len(sys.argv) != 3:
        print("usage: zipalign.py <in.apk> <out.apk>")
        return 2
    align(sys.argv[1], sys.argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main())
