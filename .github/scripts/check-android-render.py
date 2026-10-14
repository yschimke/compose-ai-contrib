"""Check a Robolectric render of `bazel-apk`'s Greeting resolved its Android resources.

The preview draws `R.drawable.proof_badge` and `R.string.greeting` on
`R.color.proof_background`. Robolectric only
resolves those through the resource table the Bazel Android aspect linked, so the
colour filling a real share of the image, the badge's colour and darker text pixels is
evidence the linked APK and final R IDs both reached the renderer. Pure stdlib: the
PNG is decoded here rather than pulling in an imaging library.
"""
import struct
import sys
import zlib
from pathlib import Path

PROOF = (0x12, 0xB4, 0xA8)
BADGE = (0xB0, 0x30, 0x6A)


def decode(path):
    data = Path(path).read_bytes()
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), f"{path} is not a PNG"
    pos, idat, header = 8, b"", None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        kind, body = data[pos + 4 : pos + 8], data[pos + 8 : pos + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    width, height, depth, colour, _, _, interlace = header
    assert depth == 8 and colour in (2, 6) and interlace == 0, f"unsupported PNG {header}"
    channels = 4 if colour == 6 else 3
    raw, stride, rows, prev = zlib.decompress(idat), width * channels, [], bytearray(width * channels)
    for y in range(height):
        start = y * (stride + 1)
        kind, line = raw[start], bytearray(raw[start + 1 : start + 1 + stride])
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up, corner = prev[i], prev[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + (left + up) // 2) & 0xFF
            elif kind == 4:
                p = left + up - corner
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
                pred = left if pa <= pb and pa <= pc else up if pb <= pc else corner
                line[i] = (line[i] + pred) & 0xFF
        # RGBA throughout: an RGB image is fully opaque.
        rows.append(
            [tuple(line[x * channels : x * channels + 3]) + ((line[x * channels + 3],) if channels == 4 else (255,)) for x in range(width)]
        )
        prev = line
    return width, height, rows


def main(path):
    width, height, rows = decode(path)
    pixels = [p for row in rows for p in row]
    near = lambda p, c, tol: all(abs(a - b) <= tol for a, b in zip(p, c))
    background = sum(p[3] >= 250 and near(p[:3], PROOF, 6) for p in pixels)
    # Opaque only: transparent padding decodes as black and is not text.
    dark = sum(p[3] >= 200 and sum(p[:3]) < 200 for p in pixels)
    badge = sum(p[3] >= 250 and near(p[:3], BADGE, 6) for p in pixels)
    print(f"{path}: {width}x{height}, proof colour {background}px, badge {badge}px, dark {dark}px")
    assert background >= len(pixels) // 4, "R.color.proof_background did not fill the preview"
    assert badge >= 64, "R.drawable.proof_badge did not draw"
    assert dark > 0, "no text drawn: R.string.greeting did not render"


if __name__ == "__main__":
    main(sys.argv[1])
