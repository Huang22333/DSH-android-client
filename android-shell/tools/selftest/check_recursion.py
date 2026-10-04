#!/usr/bin/env python3
"""直接自递归检查：方法体里**直接调用自己**（几乎总是脚本误改的产物）。

起因：我用脚本改代码时把 `card.dialog.show();` 误弄成了 `showCard(card);`。
编译完全通过（语义错误编译器不管），直到在某平板（新装、无保存地址 → 启动即弹首次进入
对话框）上以 `StackOverflowError` 闪退才暴露。

判据（只抓"直接"自递归，宁可漏也不误报）：
  · 先剥离字符串字面量与注释（否则注入的 JS 里 `function(){...}` 会被当成方法定义）；
  · 只认行首（可带修饰符）的方法定义；
  · 方法体里出现 `名字(`，且**前面不是 `.` 或标识符字符** —— `ring.start(` 这种调用不算。
"""
import re
import sys
import glob

KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try', 'else',
            'do', 'return', 'new', 'super', 'this'}


def strip_noise(src: str) -> str:
    """去掉字符串/字符字面量与注释，保留换行以便定位。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            quote = c
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                if src[i] == '\n':      # 未闭合，按行截断，别把后面全吃掉
                    break
                i += 1
            out.append('S')
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


DEF = re.compile(
    r'(?m)^[ \t]*(?:(?:public|private|protected|static|final|synchronized|abstract|native|strictfp)\s+)*'
    r'(?:[\w.$<>\[\],\s]+\s+)?([A-Za-z_]\w*)\s*\(([^;{)]*)\)\s*(?:throws [\w.,\s]+)?\{')


def signatures(src: str):
    """名字 → 参数表集合。同名多签名 = 重载，那种同名调用是合法的，要跳过。"""
    table = {}
    for m in DEF.finditer(src):
        name = m.group(1)
        if name in KEYWORDS:
            continue
        params = ','.join(x.strip() for x in m.group(2).split(',') if x.strip())
        table.setdefault(name, set()).add(params)
    return table


def methods(src: str):
    """产出 (名字, 行号, 方法体)。"""
    for m in DEF.finditer(src):
        name = m.group(1)
        if name in KEYWORDS:
            continue
        depth, j = 0, m.end() - 1
        for j in range(m.end() - 1, len(src)):
            if src[j] == '{':
                depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0:
                    break
        yield name, src[:m.start()].count('\n') + 1, src[m.end():j]


def main() -> int:
    bad = []
    for path in sorted(glob.glob('app/java/app.dsh.shell/*.java')):
        src = strip_noise(open(path, encoding='utf-8').read())
        overloaded = {n for n, sigs in signatures(src).items() if len(sigs) > 1}
        for name, line, body in methods(src):
            if name in overloaded:
                continue                      # 重载：同名调用是合法的（fit/round/card/…）
            if re.search(r'(?<![\w.])' + re.escape(name) + r'\s*\(', body):
                bad.append(f'{path}:{line}  {name}() 直接调用了自己')
    if bad:
        print('❌ 发现直接自递归（很可能是脚本误改）：')
        for b in bad:
            print('   ' + b)
        return 1
    print('ALL PASS (无直接自递归)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
