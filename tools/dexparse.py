#!/usr/bin/env python3
"""Minimal DEX symbol dumper: class -> method/proto, marking methods that have code.

Usage:
  python dexparse.py <file.dex> [--strings] [--filter SUBSTR]

Output lines:  <method|field> <class>.<name><proto> code=<0xoff|none>
Only non-abstract/native methods carry a code offset, so `code=` tells you
whether the symbol is a real implementation you can hook.
"""
import struct
import sys


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def read_string(buf, off):
    _, off = uleb128(buf, off)
    end = buf.index(b"\x00", off)
    raw = buf[off:end]
    # MUTF-8 -> replace invalid sequences instead of failing
    return raw.decode("utf-8", "replace")


class Dex:
    def __init__(self, path):
        with open(path, "rb") as fh:
            self.buf = buf = fh.read()
        if buf[:4] != b"dex\n":
            raise ValueError("not a dex file (maybe compact dex?): %r" % buf[:8])
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", buf, 0x38)

    def _u32(self, off):
        return struct.unpack_from("<I", self.buf, off)[0]

    def string(self, idx):
        if idx == 0xFFFFFFFF or idx >= self.string_ids_size:
            return "<invalid>"
        return read_string(self.buf, self._u32(self.string_ids_off + 4 * idx))

    def type_desc(self, idx):
        if idx == 0xFFFFFFFF or idx >= self.type_ids_size:
            return "<invalid>"
        return self.string(self._u32(self.type_ids_off + 4 * idx))

    def proto(self, idx):
        if idx >= self.proto_ids_size:
            return ("", "")
        shorty_idx, return_type_idx, params_off = struct.unpack_from(
            "<3I", self.buf, self.proto_ids_off + 12 * idx)
        params = []
        if params_off:
            size = self._u32(params_off)
            for i in range(size):
                params.append(self.type_desc(self._u32(params_off + 4 + 4 * i)))
        return (self.type_desc(return_type_idx), ",".join(params))

    def methods(self):
        for i in range(self.method_ids_size):
            off = self.method_ids_off + 8 * i
            class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.buf, off)
            yield i, self.type_desc(class_idx), self.string(name_idx), self.proto(proto_idx)

    def fields(self):
        for i in range(self.field_ids_size):
            off = self.field_ids_off + 8 * i
            class_idx, type_idx, name_idx = struct.unpack_from("<HHI", self.buf, off)
            yield self.type_desc(class_idx), self.string(name_idx), self.type_desc(type_idx)

    def _class_data(self, class_idx):
        off = self.class_defs_off + 32 * class_idx
        class_idx_v, access, superclass_idx, interfaces_off, source_file_idx, \
            annotations_off, class_data_off, static_values_off = struct.unpack_from("<8I", self.buf, off)
        return class_data_off

    def methods_with_code(self):
        """Return the set of absolute method_ids that have an implementation body.

        DEX encodes the method index as a delta from the previous entry, and the
        running counter restarts at each class_data_item, so it must be
        accumulated per class.
        """
        have_code = set()
        for cd in range(self.class_defs_size):
            off = self._class_data(cd)
            if off == 0:
                continue
            try:
                static_fields_size, off = uleb128(self.buf, off)
                instance_fields_size, off = uleb128(self.buf, off)
                direct_methods_size, off = uleb128(self.buf, off)
                virtual_methods_size, off = uleb128(self.buf, off)
                for _ in range(static_fields_size + instance_fields_size):
                    _, off = uleb128(self.buf, off)
                    _, off = uleb128(self.buf, off)
                # direct_methods and virtual_methods are two separate lists and
                # each restarts its own method_idx_diff accumulation at 0.
                for size in (direct_methods_size, virtual_methods_size):
                    cur = 0
                    for i in range(size):
                        diff, off = uleb128(self.buf, off)
                        cur = diff if i == 0 else cur + diff
                        _, off = uleb128(self.buf, off)
                        code_off, off = uleb128(self.buf, off)
                        if code_off:
                            have_code.add(cur)
            except (IndexError, struct.error, ValueError):
                continue
        return have_code

    def all_strings(self):
        for i in range(self.string_ids_size):
            yield self.string(i)


def main():
    path = sys.argv[1]
    do_strings = "--strings" in sys.argv
    filt = None
    if "--filter" in sys.argv:
        filt = sys.argv[sys.argv.index("--filter") + 1]

    d = Dex(path)
    if do_strings:
        for s in d.all_strings():
            if filt is None or filt.lower() in s.lower():
                print(s)
        return

    # map diff-coded values back is fiddly; instead just check each method_id
    # against the set of indexes that appeared with code (see note below).
    have_code = d.methods_with_code()
    for mid, cls, name, (ret, params) in d.methods():
        if filt and filt.lower() not in (cls + "." + name).lower():
            continue
        code = "code" if mid in have_code else "none"
        print("method %s.%s(%s)%s %s" % (cls, name, params, ret, code))


if __name__ == "__main__":
    main()
