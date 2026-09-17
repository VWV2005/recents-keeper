#!/usr/bin/env python3
"""Convert `dexdump` output into a compact symbol table.

dexdump's default output is a nested, indented report. This flattens it to one
line per member:

    c <class>                                 # class declaration
    m <class>.<name> <type> code=yes|none     # method
    f <class>.<name> <type>                   # field

A method body is rendered by dexdump as `code          -` (followed by
registers/insns size), while abstract/native/interface declarations render as
`code          : (none)`. Only `code=yes` methods are hookable.
"""
import re
import sys

CLASS_RE = re.compile(r"Class descriptor\s*:\s*'([^']*)'")
NAME_RE = re.compile(r"name\s*:\s*'(.*)'\s*$")
TYPE_RE = re.compile(r"type\s*:\s*'(.*)'\s*$")
CODE_YES_RE = re.compile(r"^code\s*-\s*$")
CODE_NONE_RE = re.compile(r"^code\s*:\s*\(none\)\s*$")
SECTION_RE = re.compile(r"^(Direct methods|Virtual methods|Static fields|Instance fields)\s*-?\s*$")


def main():
    # dexdump emits raw MUTF-8 string data, which is not always valid UTF-8
    out = sys.stdout
    cls = None
    section = None
    buf = [None]

    def flush():
        rec = buf[0]
        buf[0] = None
        if not rec or cls is None or section is None:
            return
        if section.endswith("fields"):
            out.write("f %s.%s %s\n" % (cls, rec["name"], rec.get("type", "?")))
        else:
            out.write("m %s.%s %s code=%s\n"
                      % (cls, rec["name"], rec.get("type", "?"),
                         "yes" if rec.get("code") else "none"))

    for line in sys.stdin.buffer:
        line = line.decode("utf-8", "replace").rstrip("\r\n")
        stripped = line.strip()

        m = CLASS_RE.search(line)
        if m:
            flush()
            cls = m.group(1)
            section = None
            out.write("c %s\n" % cls)
            continue

        m = SECTION_RE.match(stripped)
        if m:
            flush()
            section = m.group(1)
            continue

        if cls is None or section is None:
            continue

        m = NAME_RE.search(line)
        if m:
            flush()
            buf[0] = {"name": m.group(1)}
            continue

        if buf[0] is None:
            continue

        m = TYPE_RE.search(line)
        if m:
            buf[0]["type"] = m.group(1)
            continue

        if CODE_YES_RE.match(stripped):
            buf[0]["code"] = True
            continue

        if CODE_NONE_RE.match(stripped):
            buf[0]["code"] = False

    flush()


if __name__ == "__main__":
    main()
