#!/usr/bin/env python3
"""CaptureProvider 路径解析的用例（脱机可跑，不需要安卓设备）。

CaptureProvider.resolve() 是纯字符串/路径判断，但它在安卓里；这台机器没有设备、
也跑不了 Instrumentation 测试。所以把那段判定**逐条对照**用 Python 重写，
用同一批 URI 校验放行结果。

⚠️ 这不是在执行真 Java 代码，而是**把判定规则钉死**：Java 侧改了条件，
必须同步改这里，否则两边悄悄分叉。真正的护栏仍是实机 instrumented test。
"""
import posixpath
import sys

AUTH = "app.dsh.shell.fileprovider"
DIR = "/data/user/0/app.dsh.shell/cache/capture"


def resolve_impl(authority, seg, strict=True):
    """对照 CaptureProvider.resolve()。strict=False 模拟「少写了一道防护」。"""
    if authority != AUTH:
        return False
    if seg is None or seg == "":
        return False
    if "/" in seg or "\\" in seg or (strict and ".." in seg):
        return False
    full = posixpath.normpath(DIR + "/" + seg) if strict else DIR + "/" + seg
    if not full.startswith(DIR + "/"):
        return False
    return True


def open_ok(authority, seg, mode, exists):
    """
    对照 CaptureProvider.openFile()：能否成功打开。
    ★ 关键规则：**写模式不要求文件已存在**（相机拍照就是新建文件再写入）。
      早期版本在 resolve() 里加了 isFile() 检查 ⇒ 相机写不进去 ⇒ 拍完等于没拍。
    """
    if not resolve_impl(authority, seg):
        return False
    write = mode is not None and ("w" in mode)
    if not write and not exists:
        return False          # 读：文件必须存在
    return True               # 写：可以不存在，靠 MODE_CREATE 建


CASES = [
    (AUTH, "dsh-123.jpg", True, "正常拍照文件名"),
    (AUTH, "a b.jpg", True, "带空格"),
    (AUTH, "中文名.jpg", True, "中文名"),
    (AUTH, "..", False, "父目录（末段就是 ..）"),
    (AUTH, "../../etc/passwd", False, "目录穿越"),
    (AUTH, "a/b.jpg", False, "带斜杠"),
    (AUTH, "a\\b.jpg", False, "带反斜杠"),
    (AUTH, "..%2fetc%2fpasswd", False, "编码后的 ..，字面含 .. 即拒"),
    (AUTH, "a..b.jpg", False, "名字含 .. 一律拒（收紧，宁可误拒）"),
    (AUTH, "", False, "空段"),
    (AUTH, None, False, "无段"),
    ("com.evil.app.fileprovider", "dsh-1.jpg", False, "别的应用冒用 authority"),
    ("", "dsh-1.jpg", False, "空 authority"),
    (AUTH + ".evil", "dsh-1.jpg", False, "authority 后缀欺骗"),
]


def run_impl(fn, verbose):
    fails = 0
    for auth, seg, want, desc in CASES:
        got = fn(auth, seg)
        if got != want:
            fails += 1
            if verbose:
                print("FAIL %-30s 放行=%-5s 期望=%-5s %s"
                      % (repr(seg)[:30], got, want, desc))
        elif verbose:
            print("ok   %-30s 放行=%-5s %s" % (repr(seg)[:30], got, desc))
    return fails


def open_mode_checks():
    """相机拍照路径的回归测试（这个 bug 真机复现过）。"""
    fails = 0
    cases = [
        # (说明, authority, 文件名, mode, 文件是否已存在, 期望能打开)
        ("★ 拍照写新文件（相机流程）", AUTH, "dsh-1.jpg", "w", False, True),
        ("★ 拍照写新文件 rw",         AUTH, "dsh-1.jpg", "rw", False, True),
        ("读已存在的拍照文件",         AUTH, "dsh-1.jpg", "r", True, True),
        ("读不存在的文件（应失败）",    AUTH, "nope.jpg", "r", False, False),
        ("读压缩产物",                AUTH, "dsh-c.jpg", "r", True, True),
        ("写模式也不能越权路径",        AUTH, "../../x.jpg", "w", False, False),
        ("写模式也要校验 authority",   "com.evil.x", "dsh-1.jpg", "w", False, False),
    ]
    for desc, auth, seg, mode, exists, want in cases:
        got = open_ok(auth, seg, mode, exists)
        mark = "ok  " if got == want else "FAIL"
        if got != want:
            fails += 1
        print("%s %-28s mode=%-3s exists=%-5s 可打开=%-5s 期望=%s"
              % (mark, desc, mode, exists, got, want))
    return fails


def meta_check():
    """
    元验证：把防护放宽后用例集**必须变红**，否则说明用例测不到防护、是摆设。
    strict=False 同时去掉 `..` 判定与路径规范化，应能让穿越类用例失败。
    """
    weak = run_impl(lambda a, s: resolve_impl(a, s, strict=False), False)
    if weak == 0:
        print("FAIL 元验证：放宽防护后用例仍全绿 ⇒ 用例测不到防护")
        return 1
    print("ok   元验证：放宽防护后 %d 条用例变红（防护确实被覆盖）" % weak)
    return 0


def main():
    failures = meta_check()
    failures += open_mode_checks()
    failures += run_impl(resolve_impl, True)
    print("ALL PASS" if failures == 0 else "%d FAILED" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
