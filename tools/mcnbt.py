"""Minimal NBT and region-file reading, enough to read chunks out of a world.

Self-contained so the verification scripts work from a clone with nothing but
python and a Minecraft world directory.
"""
import gzip
import struct
import zlib


def read_nbt(f):
    """Reads a single NBT tag tree from a Reader."""

    def u1():
        return f.read(1)[0]

    def u2():
        return struct.unpack('>H', f.read(2))[0]

    def i4():
        return struct.unpack('>i', f.read(4))[0]

    def name():
        return f.read(u2()).decode('utf8', 'replace')

    def payload(t):
        if t == 0:
            return None
        if t == 1:
            return struct.unpack('>b', f.read(1))[0]
        if t == 2:
            return struct.unpack('>h', f.read(2))[0]
        if t == 3:
            return i4()
        if t == 4:
            return struct.unpack('>q', f.read(8))[0]
        if t == 5:
            return struct.unpack('>f', f.read(4))[0]
        if t == 6:
            return struct.unpack('>d', f.read(8))[0]
        if t == 7:
            return f.read(i4())
        if t == 8:
            return f.read(u2()).decode('utf8', 'replace')
        if t == 9:
            item = u1()
            n = i4()
            return [payload(item) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = u1()
                if tt == 0:
                    return out
                # Read the name before the payload. Python evaluates the right-hand
                # side of an assignment first, so `out[name()] = payload(tt)` would
                # consume the payload bytes as the name.
                key = name()
                out[key] = payload(tt)
        if t == 11:
            return [i4() for _ in range(i4())]
        if t == 12:
            return [struct.unpack('>q', f.read(8))[0] for _ in range(i4())]
        raise ValueError('unknown tag %d' % t)

    tag = u1()
    name()
    return payload(tag)


class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def read(self, n):
        b = self.d[self.i:self.i + n]
        self.i += n
        return b


def read_chunk(region_path, cx, cz):
    """Returns the chunk's Level compound, or None if it is not in this region."""
    try:
        with open(region_path, 'rb') as f:
            header = f.read(4096)
            f.read(4096)
            blob = f.read()
        if len(header) < 4096:
            return None
    except OSError:
        return None
    index = (cx & 31) + (cz & 31) * 32
    try:
        offset = struct.unpack('>I', b'\x00' + header[index * 4:index * 4 + 3])[0]
        if offset == 0:
            return None
        start = offset * 4096 - 8192
        if start < 0 or start + 5 > len(blob):
            return None
        length = struct.unpack('>i', blob[start:start + 4])[0]
        if length <= 0 or start + 4 + length > len(blob):
            return None
        compression = blob[start + 4]
        raw = blob[start + 5:start + 4 + length]
        if not raw:
            return None
        data = zlib.decompress(raw) if compression == 2 else gzip.decompress(raw)
        return read_nbt(Reader(data))['Level']
    except (struct.error, IndexError, zlib.error, OSError, KeyError):
        return None


def block_grid(level, max_y=255):
    """{(localX, y, localZ): block name} for one chunk, names without the namespace."""
    grid = {}
    for section in level.get('Sections', []):
        palette = section.get('Palette')
        states = section.get('BlockStates')
        y0 = section.get('Y')
        if palette is None or states is None or y0 is None or y0 < 0 or y0 * 16 > max_y:
            continue
        names = [p['Name'].replace('minecraft:', '') for p in palette]
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        mask = (1 << bits) - 1
        for i in range(4096):
            y = y0 * 16 + (i >> 8)
            if y > max_y:
                break
            slot = i // per_long
            if slot >= len(states):
                break
            index = (states[slot] >> ((i % per_long) * bits)) & mask
            if index < len(names):
                grid[(i & 15, y, (i >> 4) & 15)] = names[index]
    return grid
