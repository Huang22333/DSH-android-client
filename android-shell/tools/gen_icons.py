#!/usr/bin/env python3
"""生成全套应用图标（从 DSH 的 favicon.svg 渲染鲸鱼）。

留白是这里唯一的调参点，两套图标的可见区域不同，不能共用同一个值：

  · legacy 图标（mipmap-*/ic_launcher.png）
    整块 48dp 都可见，没有系统遮罩。内容占画布 ~62%。

  · 自适应图标前景（mipmap-*/ic_launcher_foreground.png）
    108dp 画布，但系统 mask 只显示中间 ~72dp（66.7%）。所以画布上的内容占比
    要换算：占可见区 71% 对应画布占比 71% * 66.7% ≈ 47%。早期版本这里给了 63%，
    实际可见区占比 95% —— 看起来就是"鲸鱼撑满整个图标"，正是用户嫌丑的原因。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg2png import render  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SVG = os.path.join(ROOT, "assets", "whale.svg")

LEGACY = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
ADAPTIVE = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]

LEGACY_MARGIN = 0.30      # → 内容占画布 62.5%
ADAPTIVE_MARGIN = 0.55    # → 内容占画布 47.6%，即可见区约 71%


def main():
    if not os.path.exists(SVG):
        sys.exit("缺 %s（从 DSH 的 /favicon.svg 取）" % SVG)
    for d, sz in LEGACY:
        p = os.path.join(ROOT, "app", "res", "mipmap-%s" % d, "ic_launcher.png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        render(SVG, p, size=sz, margin=LEGACY_MARGIN)
    for d, sz in ADAPTIVE:
        p = os.path.join(ROOT, "app", "res", "mipmap-%s" % d, "ic_launcher_foreground.png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        render(SVG, p, size=sz, margin=ADAPTIVE_MARGIN)
    print("icons: legacy margin=%.2f (%.0f%%)  adaptive margin=%.2f (%.0f%% of canvas)"
          % (LEGACY_MARGIN, 100 / (1 + 2 * LEGACY_MARGIN),
             ADAPTIVE_MARGIN, 100 / (1 + 2 * ADAPTIVE_MARGIN)))


if __name__ == "__main__":
    main()
