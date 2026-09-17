#!/usr/bin/env python3
"""Stream a `dexdump -d` dump and print only the class blocks you ask for.

Usage: dexdump -d classes.dex | python extract_class.py 'Lcom/foo/Bar;' ...

Streaming keeps memory flat on multi-hundred-MB disassembly, which matters
because dexdump has no way to select a single class itself.
"""
import re
import sys

CLASS_HDR_RE = re.compile(r"^Class #\d+")
DESC_RE = re.compile(r"Class descriptor\s*:\s*'([^']*)'")


def main():
    targets = set(sys.argv[1:])
    if not targets:
        sys.stderr.write("no class descriptors given\n")
        return 1

    out = sys.stdout
    block = []
    matched = False
    emitted_header = False

    def flush():
        nonlocal matched, block, emitted_header
        if matched:
            if emitted_header:
                out.write("\n")
            out.write("".join(block))
            emitted_header = True
        block = []
        matched = False

    for line in sys.stdin.buffer:
        text = line.decode("utf-8", "replace")
        if CLASS_HDR_RE.match(text):
            flush()
            block = []
            matched = False
        block.append(text)
        m = DESC_RE.search(text)
        if m and m.group(1) in targets:
            matched = True

    flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
