"""Small bounds-checked offset inventory; semantic parsing is independently cross-checked.

This scanner only recovers exact Code instruction byte offsets for the plan's
BCI anchors. It is never used as an operand or metadata oracle.
"""
import struct


class Reader:
    def __init__(self, data):
        self.data, self.p = data, 0

    def take(self, count):
        if count < 0 or self.p + count > len(self.data):
            raise ValueError("truncated classfile/attribute")
        out = self.data[self.p:self.p + count]
        self.p += count
        return out

    def u(self, count):
        return int.from_bytes(self.take(count), "big")


def offsets(code):
    r, out = Reader(code), []
    while r.p < len(code):
        pc = r.p
        out.append(pc)
        op = r.u(1)
        if op > 201:
            raise ValueError("reserved opcode")
        if op in (170, 171):
            padding = r.take((-r.p) % 4)
            if any(padding):
                raise ValueError("nonzero switch padding")
            r.take(4)
            if op == 170:
                low, high = struct.unpack(">ii", r.take(8))
                n = high - low + 1
                if n < 1 or n > len(code) // 4:
                    raise ValueError("invalid tableswitch bound")
                r.take(n * 4)
            else:
                n = r.u(4)
                if n > len(code) // 8:
                    raise ValueError("invalid lookupswitch bound")
                r.take(n * 8)
        elif op == 196:
            wide = r.u(1)
            if wide not in (21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 132, 169):
                raise ValueError("invalid wide opcode")
            r.take(4 if wide == 132 else 2)
        else:
            extra = (4 if op in (185, 186, 200, 201) else 3 if op == 197 else
                     2 if op in (17, 19, 20, 132) or 153 <= op <= 168 or
                     178 <= op <= 184 or op in (187, 189, 192, 193, 198, 199) else
                     1 if op in (16, 18, 169, 188) or 21 <= op <= 25 or 54 <= op <= 58 else 0)
            r.take(extra)
    return out


def method_offsets(data):
    r = Reader(data)
    if r.take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("bad class magic")
    r.take(4)
    count, cp, i = r.u(2), {}, 1
    while i < count:
        tag = r.u(1)
        if tag == 1:
            # Method/attribute names and descriptors in the supported plan are ASCII.
            cp[i] = r.take(r.u(2))
        elif tag in (3, 4):
            r.take(4)
        elif tag in (5, 6):
            r.take(8)
            i += 1
        elif tag in (7, 8, 16):
            r.take(2)
        elif tag in (9, 10, 11, 12, 18):
            r.take(4)
        elif tag == 15:
            r.take(3)
        else:
            raise ValueError("unsupported constant tag")
        i += 1
    r.take(6)
    r.take(r.u(2) * 2)

    def attrs(reader):
        return [(cp[reader.u(2)], reader.take(reader.u(4))) for _ in range(reader.u(2))]

    for _ in range(r.u(2)):
        r.take(6)
        attrs(r)
    result = {}
    for _ in range(r.u(2)):
        r.take(2)
        key = (cp[r.u(2)].decode("ascii"), cp[r.u(2)].decode("ascii"))
        if key in result:
            raise ValueError("duplicate method")
        found = [raw for name, raw in attrs(r) if name == b"Code"]
        if len(found) > 1:
            raise ValueError("duplicate Code")
        if found:
            cr = Reader(found[0])
            cr.take(4)
            code = cr.take(cr.u(4))
            result[key] = offsets(code)
        else:
            result[key] = []
    attrs(r)
    if r.p != len(data):
        raise ValueError("trailing class bytes")
    return result
