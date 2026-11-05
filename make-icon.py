import zlib, struct, os

S, SS = 40, 4

def rr(x, y, x0, y0, x1, y1, r):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r

def sample(x, y):
    if not rr(x, y, 1, 1, 39, 39, 9):
        return None
    t = (x + y) / 80.0
    col = (int(10 + (94 - 10) * t), int(132 + (92 - 132) * t), int(255 + (230 - 255) * t))
    if rr(x, y, 11, 7, 29, 33, 3):
        if x > 22 and y < 14 and (x - 22) > (y - 7):
            pass
        elif x > 22 and y < 14:
            col = (196, 208, 245)
        else:
            col = (255, 255, 255)
            for (ya, yb, xb) in ((17, 18.7, 26), (21, 22.7, 26), (25, 26.7, 22)):
                if 14 <= x <= xb and ya <= y <= yb:
                    col = (10, 132, 255)
    return col

raw = bytearray()
for py in range(S):
    raw.append(0)
    for px in range(S):
        r = g = b = a = 0
        for sy in range(SS):
            for sx in range(SS):
                c = sample(px + (sx + 0.5) / SS, py + (sy + 0.5) / SS)
                if c:
                    r += c[0]; g += c[1]; b += c[2]; a += 1
        n = SS * SS
        if a:
            raw += bytes((r // a, g // a, b // a, 255 * a // n))
        else:
            raw += bytes((0, 0, 0, 0))

def chunk(t, d):
    c = struct.pack('>I', len(d)) + t + d
    return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', S, S, 8, 6, 0, 0, 0)) \
      + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
os.makedirs('res', exist_ok=True)
open('res/icon.png', 'wb').write(png)
print('icon ok', len(png))
