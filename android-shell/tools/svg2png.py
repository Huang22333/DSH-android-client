#!/usr/bin/env python3
"""极简 SVG(M/C/Z 路径) -> PNG 渲染器（纯标准库）。

只做一件事：把 dsh favicon 的鲸鱼轮廓渲染成带抗锯齿的 PNG 图标。
不做通用 SVG 支持。用法见文件末尾 __main__。
"""
import re
import struct
import sys
import zlib


def parse_path(d):
    """返回子路径列表，每条是线段点列 [(x,y), ...]（C 已离散化）。"""
    toks = re.findall(r'[MCZmc]|-?\d*\.?\d+(?:e-?\d+)?', d)
    subs, cur, pts = [], None, []
    i = 0
    while i < len(toks):
        t = toks[i]
        if t in 'Mm':
            if pts:
                subs.append(pts)
            x, y = float(toks[i + 1]), float(toks[i + 2])
            pts = [(x, y)]
            i += 3
        elif t in 'Cc':
            x1, y1, x2, y2, x, y = (float(v) for v in toks[i + 1:i + 7])
            x0, y0 = pts[-1]
            for k in range(1, 17):
                u = k / 16.0
                v = 1 - u
                bx = (v ** 3) * x0 + 3 * (v ** 2) * u * x1 + 3 * v * (u ** 2) * x2 + (u ** 3) * x
                by = (v ** 3) * y0 + 3 * (v ** 2) * u * y1 + 3 * v * (u ** 2) * y2 + (u ** 3) * y
                pts.append((bx, by))
            i += 7
        elif t in 'Zz':
            if pts:
                pts.append(pts[0])
            i += 1
        else:
            i += 1
    if pts:
        subs.append(pts)
    return subs


def rasterize(subs, w, h, ss=4, xform=None):
    """超采样扫描线填充，返回 w*h 的 alpha 列表(0..255)。奇偶规则。"""
    W, H = w * ss, h * ss
    cov = bytearray(W * H)
    for pts in subs:
        p = [xform(x, y) for (x, y) in pts] if xform else pts
        p = [(x * ss, y * ss) for (x, y) in p]
        ys = [q[1] for q in p]
        ymin, ymax = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
        n = len(p)
        for py in range(ymin, ymax + 1):
            yc = py + 0.5
            xs = []
            for k in range(n - 1):
                (x1, y1), (x2, y2) = p[k], p[k + 1]
                if (y1 <= yc < y2) or (y2 <= yc < y1):
                    xs.append(x1 + (yc - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for k in range(0, len(xs) - 1, 2):
                a, b = xs[k], xs[k + 1]
                xa, xb = max(0, int(a)), min(W - 1, int(b))
                for px in range(xa, xb + 1):
                    cov[py * W + px] ^= 1
    # 降采样
    out = bytearray(w * h)
    for y in range(h):
        for x in range(w):
            s = 0
            for dy in range(ss):
                row = (y * ss + dy) * W + x * ss
                for dx in range(ss):
                    s += cov[row + dx]
            out[y * w + x] = (s * 255) // (ss * ss)
    return out


def write_png(path, w, h, rgba):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * w * 4:(y + 1) * w * 4]

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def render(svg_path, out_path, size=432, color=(17, 24, 39), margin=0.10, bg=None,
           fit=None):
    """margin: 四周留白比例；fit=(x0,y0,x1,y1) 可强制裁剪框，默认按路径实际包围盒。"""
    d = re.search(r'<path[^>]*\bd="([^"]+)"', open(svg_path).read()).group(1)
    subs = parse_path(d)
    xs = [p[0] for s in subs for p in s]
    ys = [p[1] for s in subs for p in s]
    x0, y0, x1, y1 = (min(xs), min(ys), max(xs), max(ys)) if fit is None else fit
    side = max(x1 - x0, y1 - y0) * (1 + 2 * margin)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    xf = lambda x, y: ((x - cx) / side * size + size / 2.0,
                       (y - cy) / side * size + size / 2.0)
    alpha = rasterize(subs, size, size, ss=3, xform=xf)
    rgba = bytearray(size * size * 4)
    r, g, b = color
    for i in range(size * size):
        a = alpha[i]
        if bg is not None:
            br, bgc, bb = bg
            rgba[i * 4 + 0] = (r * a + br * (255 - a)) // 255
            rgba[i * 4 + 1] = (g * a + bgc * (255 - a)) // 255
            rgba[i * 4 + 2] = (b * a + bb * (255 - a)) // 255
            rgba[i * 4 + 3] = 255
        else:
            rgba[i * 4 + 0] = r
            rgba[i * 4 + 1] = g
            rgba[i * 4 + 2] = b
            rgba[i * 4 + 3] = a
    write_png(out_path, size, size, rgba)
    return out_path


if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    render(src, dst)
    print('wrote', dst)
