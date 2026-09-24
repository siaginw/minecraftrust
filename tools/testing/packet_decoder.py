#!/usr/bin/env python3
"""Strict, standalone Protocol-340 chunk-section reader (no encoder imports).

This reader qualifies the project's bounded 4..8-bit local / 9..16-bit global
wire format. It does not attest to live Java capture ownership or mod safety.
"""
from dataclasses import dataclass


class DecodeError(ValueError):
    pass


@dataclass(frozen=True)
class DecodedSection:
    y: int
    bits: int
    palette: tuple
    states: tuple
    block_light: bytes
    sky_light: bytes | None


@dataclass(frozen=True)
class DecodedPacket:
    sections: tuple
    biomes: bytes | None
    consumed: int
    emitted_mask: int


class Reader:
    def __init__(self, data):
        if not isinstance(data, bytes):
            raise DecodeError("payload must be immutable bytes")
        self.data = data
        self.offset = 0

    def take(self, size):
        if size < 0 or size > len(self.data) - self.offset:
            raise DecodeError("truncated payload")
        start = self.offset
        self.offset += size
        return self.data[start:self.offset]

    def varint(self):
        value = 0
        for index in range(5):
            byte = self.take(1)[0]
            if index == 4 and byte & 0xF0:
                raise DecodeError("VarInt exceeds unsigned 32-bit storage")
            value |= (byte & 0x7F) << (7 * index)
            if byte < 0x80:
                if index and value < (1 << (7 * index)):
                    raise DecodeError("noncanonical VarInt")
                return value
        raise DecodeError("unterminated VarInt")


def unpack_cells(words, bits, palette=None):
    """Read contiguous LSB-first entries spanning big-endian serialized words."""
    if type(bits) is not int or not 1 <= bits <= 32:
        raise DecodeError("unsupported packed width")
    if len(words) != (4096 * bits + 63) // 64:
        raise DecodeError("incorrect packed-word count")
    states = []
    for cell in range(4096):
        position = cell * bits
        word, shift = divmod(position, 64)
        value = words[word] >> shift
        if shift + bits > 64:
            value |= words[word + 1] << (64 - shift)
        value &= (1 << bits) - 1
        if palette is not None:
            if value >= len(palette):
                raise DecodeError("palette index out of range")
            value = palette[value]
        states.append(value)
    return tuple(states)


def decode_packet(data, emitted_mask, full_chunk, skylight, global_bits):
    if type(emitted_mask) is not int or not 0 <= emitted_mask <= 0xFFFF:
        raise DecodeError("invalid emitted mask")
    if type(full_chunk) is not bool or type(skylight) is not bool:
        raise DecodeError("flags must be booleans")
    if type(global_bits) is not int or not 9 <= global_bits <= 16:
        raise DecodeError("unsupported global palette width")
    reader = Reader(data)
    sections = []
    for y in range(16):
        if not emitted_mask & (1 << y):
            continue
        bits = reader.take(1)[0]
        if not 4 <= bits <= 16:
            raise DecodeError("unsupported wire width")
        count = reader.varint()
        if bits <= 8:
            if not 1 <= count <= (1 << bits):
                raise DecodeError("invalid local palette length")
            palette = tuple(reader.varint() for _ in range(count))
            if len(set(palette)) != len(palette):
                raise DecodeError("duplicate local palette entry")
        else:
            if bits != global_bits or count != 0:
                raise DecodeError("global palette width or length mismatch")
            palette = None
        word_count = reader.varint()
        if word_count != (4096 * bits + 63) // 64:
            raise DecodeError("incorrect wire word count")
        packed = reader.take(word_count * 8)
        words = tuple(int.from_bytes(packed[i:i + 8], "big") for i in range(0, len(packed), 8))
        states = unpack_cells(words, bits, palette)
        block_light = reader.take(2048)
        sky_light = reader.take(2048) if skylight else None
        sections.append(DecodedSection(y, bits, () if palette is None else palette,
                                       states, block_light, sky_light))
    biomes = reader.take(256) if full_chunk else None
    if reader.offset != len(data):
        raise DecodeError("trailing payload bytes or unadvertised sections")
    if len(sections) != emitted_mask.bit_count():
        raise DecodeError("section count mismatch")
    return DecodedPacket(tuple(sections), biomes, reader.offset, emitted_mask)
