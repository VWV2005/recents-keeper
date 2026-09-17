#!/usr/bin/env python3
"""Stream a `dexdump -d` dump and report every reference to given symbols.

Prints `<class> :: <enclosing method> :: <matched line>` so you can reconstruct
a call graph without keeping multi-hundred-MB disassembly on disk.

Usage: dexdump -d classes.dex | python findrefs.py canRemoveTask isCustomizedPkg
"""
import re
import sys

DESC_RE = re.compile(r"Class descriptor\s*:\s*'([^']*)'")
BODY_RE = re.compile(r"^\[[0-9a-f]+\]\s+(\S+):(\S+)")
NAME_RE = re.compile(r"^\s+name\s+:\s+'(.*)'\s*$")


def main():
    patterns = sys.argv[1:]
    if not patterns:
        sys.stderr.write("no patterns given\n")
        return 1

    cls = "?"
    method = "?"
    out = sys.stdout

    for raw in sys.stdin.buffer:
        line = raw.decode("utf-8", "replace").rstrip("\r\n")

        m = DESC_RE.search(line)
        if m:
            cls = m.group(1)
            method = "?"
            continue

        m = NAME_RE.match(line)
        if m:
            method = m.group(1)
            continue

        m = BODY_RE.match(line.strip())
        if m:
            method = "%s%s" % (m.group(1), m.group(2))
            continue

        for p in patterns:
            if p in line:
                out.write("%s :: %s :: %s\n" % (cls, method, line.strip()))
                break


if __name__ == "__main__":
    sys.exit(main())
