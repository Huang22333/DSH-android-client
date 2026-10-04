#!/usr/bin/env python3
"""把 Ui.java 的配色/圆角/间距渲染成预览图。

为什么要有这个：这台机器不能跑安卓模拟器（资源有限），
而"界面丑不丑"是纯视觉问题，看代码判断不了。所以把 Ui.java 里那套
设计常量（颜色、圆角、字号、内边距）逐字搬到 HTML 上，用无头 Chromium
按手机真实视口渲染，就能在装机前先看一眼。

⚠️ 这是"同参数复刻"，不是逐像素模拟：真实控件仍可能有细微差异
   （字体度量、涟漪、EditText 下划线等）。它只能回答"配色/层级/留白对不对"。
⚠️ 视口必须走 REST 截图（bl.sh shot <WxH>）：CDP 的 setViewportSize 会被
   browserless 窗口尺寸覆盖，innerWidth 恒 800。

用法： python3 tools/preview_ui.py [卡片名...]   默认全部
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI_JAVA = os.path.join(ROOT, "app", "java", "app", "dsh", "shell", "Ui.java")
BL = "/usr/local/bin/bl.sh"
TMP = os.path.join(ROOT, ".tmp")


def colors():
    """从 Ui.java 抽常量，保证预览与真机用同一组颜色。"""
    src = open(UI_JAVA, encoding="utf-8").read()

    def const(name, default):
        m = re.search(r"\b%s\s*=\s*(0x[0-9A-Fa-f]+)" % re.escape(name), src)
        return m.group(1) if m else default

    def css(v):
        v = int(v, 16)
        return "rgba(%d,%d,%d,%.3f)" % ((v >> 16) & 255, (v >> 8) & 255, v & 255,
                                        ((v >> 24) & 255) / 255.0)

    return {
        "card": css(const("CARD", "0xFFFFFFFF")),
        "text": css(const("TEXT", "0xFF0F172A")),
        "sub": css(const("SUB", "0xFF64748B")),
        "accent": css(const("ACCENT", "0xFF2F6BFF")),
        "field": css(const("FIELD", "0xFFF1F5F9")),
    }


CARDS = {
    "loading": """
      <div class="loadwrap">
        <img src="data:image/png;base64,__WHALE__" class="whalelogo"/>
        <div class="ring"></div>
        <div class="loadtitle">正在加载 DSH</div>
        <div class="loadsub">正在加载资源 68% · 4s</div>
      </div>""",
    "first-run": """
      <div class="title">欢迎使用 DSH</div>
      <div class="body">填写你的 DSH 网页入口地址。
必须是 https —— DSH 需要安全上下文，http 下部分功能不可用。
填好后会记住，之后打开直接进入。</div>
      <div class="label">入口地址</div>
      <div class="input placeholder">https://your-dsh.example.com/</div>
      <div class="row">
        <div class="btn ghost">退出</div>
        <div class="btn primary">开始使用</div>
      </div>""",
    "auth": """
      <div class="title">登录 DSH</div>
      <div class="body">该站点需要 HTTP Basic 认证。勾选「记住」后下次打开自动登录。</div>
      <div class="label">用户名</div>
      <div class="input">admin</div>
      <div class="label">密码</div>
      <div class="input">••••••••</div>
      <div class="check"><div class="box">✓</div>记住账号密码</div>
      <div class="row">
        <div class="btn ghost">取消</div>
        <div class="btn primary">登录</div>
      </div>""",
    "settings": """
      <div class="title">设置</div>
      <div class="body">这些设置会立刻生效并记住。</div>
      <div class="menurow" style="display:flex;align-items:center;gap:14px">
        <div style="flex:1">
          <div class="t">上传前压缩图片</div>
          <div class="d">缩到 2048 长边（约 450KB）。DSH 会直接放行不再重编码，上传快很多；模型看到的画面完全不变。</div>
        </div>
        <div class="pill">已开启</div>
      </div>
      <div class="row"><div class="btn primary">完成</div></div>""",
    "menu": """
      <div class="title">DSH</div>
      <div class="body">dsh.example.com:443</div>
      <div class="menurow"><div class="t">重新加载</div><div class="d">刷新当前页面</div></div>
      <div class="menurow"><div class="t">修改地址</div><div class="d">换一个 DSH 入口</div></div>
      <div class="menurow"><div class="t">清除账号密码</div><div class="d">同时清掉登录 Cookie</div></div>""",
}


def page(c, inner):
    return """<!doctype html><html><head><meta charset="utf-8">
<style>
  * {{ box-sizing: border-box; }}
  html,body {{ margin:0; height:100%; font-family: "Noto Sans CJK SC","PingFang SC",
    "Microsoft YaHei",system-ui,sans-serif; }}
  .scrim {{ position:fixed; inset:0; background:rgba(0,0,0,0.62);
    display:flex; align-items:center; justify-content:center; }}
  .card {{ margin:22px; padding:22px 22px 12px; background:{card};
    border-radius:26px; width:100%; }}
  .title {{ font-size:21px; font-weight:700; color:{text}; }}
  .body {{ margin-top:10px; font-size:14px; line-height:1.55; color:{sub};
    white-space:pre-line; }}
  .label {{ margin:18px 0 7px; font-size:12.5px; color:{sub}; }}
  .input {{ padding:14px 16px; background:{field}; border-radius:14px;
    font-size:16px; color:{text}; }}
  .input.placeholder {{ color:{sub}; }}
  .check {{ margin-top:14px; display:flex; align-items:center; gap:9px;
    font-size:14px; color:{text}; }}
  .box {{ width:19px; height:19px; border-radius:5px; background:{accent};
    display:flex; align-items:center; justify-content:center;
    color:#fff; font-size:13px; }}
  .row {{ margin-top:20px; display:flex; justify-content:flex-end; gap:8px; }}
  .btn {{ padding:13px 20px; border-radius:14px; font-size:15.5px; font-weight:700; }}
  .btn.primary {{ background:{accent}; color:#fff; }}
  .btn.ghost {{ color:{text}; }}
  .menurow {{ margin-top:10px; padding:14px; background:{field}; border-radius:14px; }}
  .menurow .t {{ font-size:16px; color:{text}; }}
  .menurow .d {{ margin-top:3px; font-size:12.5px; color:{sub}; }}
  .pill {{ flex:0 0 auto; padding:8px 16px; border-radius:999px; background:{accent};
    color:#fff; font-size:13px; font-weight:700; }}
</style></head><body>
<div class="scrim"><div class="card">{inner}</div></div>
</body></html>
""".format(inner=inner, **c)


def main():
    want = sys.argv[1:] or list(CARDS)
    c = colors()
    os.makedirs(TMP, exist_ok=True)
    rc = 0
    for name in want:
        html = os.path.join(TMP, "ui-%s.html" % name)
        png = os.path.join(TMP, "ui-%s.png" % name)
        open(html, "w", encoding="utf-8").write(page(c, CARDS[name]))
        r = subprocess.run([BL, "shot", "@" + html, png, "436x900",
                            '{"gotoOptions":{"waitUntil":"load"}}'],
                           capture_output=True, text=True, timeout=180)
        ok = os.path.exists(png) and os.path.getsize(png) > 2000
        print("%-10s -> %s%s" % (name, png, "" if ok else "  [失败] " + r.stderr[-200:]))
        if not ok:
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
