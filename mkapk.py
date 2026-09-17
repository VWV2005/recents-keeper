#!/usr/bin/env python3
"""Repack an aapt2-linked base APK with a freshly built classes.dex.

Every entry keeps its original ZipInfo, so resources.arsc stays uncompressed
(required on Android 11+) and zipalign can fix alignment afterwards.
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) != 4:
        sys.stderr.write("usage: mkapk.py <base.apk> <classes.dex> <out.apk>\n")
        return 2

    base, dex, out = sys.argv[1:4]
    with zipfile.ZipFile(base) as zin, \
            zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == "classes.dex":
                continue
            zout.writestr(item, zin.read(item.filename))
        zout.write(dex, "classes.dex")

    print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
