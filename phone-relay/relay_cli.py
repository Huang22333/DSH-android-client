#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""phone-relay 控制端 CLI。

用法：
  phone 状态                     看手机在不在线
  phone [-t 秒] <shell 命令...>  在手机上执行（等效 adb shell），输出直接打到终端
  phone kill <id>                杀掉远端任务

远端退出码 = 本命令退出码；超时/掉线退出码 124。
"""
import asyncio
import base64
import os
import signal
import sys

HOST = os.environ.get("PHONE_RELAY_HOST", "127.0.0.1")
PORT = int(os.environ.get("PHONE_RELAY_PORT", "2900"))
TOKEN_FILE = os.environ.get("PHONE_RELAY_TOKEN_FILE",
                            "/etc/dsh-relay/token")


def load_token():
    tok = os.environ.get("PHONE_RELAY_TOKEN")
    if not tok:
        with open(TOKEN_FILE) as f:
            tok = f.read().strip()
    return tok


async def run(argv):
    timeout = 0.0
    args = list(argv)
    while args and args[0] in ("-t", "--timeout"):
        timeout = float(args[1]) * 1000
        args = args[2:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "状态" or args[0] == "status":
        cmd_line = "STATUS"
    elif args[0] == "kill":
        cmd_line = f"KILL {args[1]}"
    else:
        cmd = " ".join(args)
        cmd_line = "RUN %s %d" % (base64.b64encode(cmd.encode()).decode(), int(timeout))

    reader, writer = await asyncio.open_connection(HOST, PORT)
    # 先发 HELLO 再等应答（relay 也是这个顺序；反过来会两边互等死锁，实测）
    writer.write(("HELLO %s CTRL\n" % load_token()).encode())
    await writer.drain()
    ack = await asyncio.wait_for(reader.readline(), 10)
    if ack.strip() != b"OK":
        sys.stderr.write("鉴权失败：" + ack.decode("utf-8", "replace"))
        return 1

    writer.write((cmd_line + "\n").encode())
    await writer.drain()

    code = 0
    jid = None
    limit = (timeout / 1000.0) or None   # RUN 行传 ms，本地等待用秒（单位别混，实测坑）
    try:
        while True:
            line = await asyncio.wait_for(reader.readline(), limit)
            if not line:
                break
            parts = line.decode("utf-8", "replace").rstrip("\n").split(" ", 3)
            tag = parts[0]
            if tag == "OK":
                if cmd_line.startswith("RUN") and len(parts) > 1:
                    jid = parts[1]
                else:
                    # STATUS / KILL 的应答打全（含 phones 数与手机名）
                    print(line.decode("utf-8", "replace").rstrip()[3:])
                    break
            elif tag == "OUT" and len(parts) == 4:
                data = base64.b64decode(parts[3].encode())
                (sys.stdout if parts[2] == "1" else sys.stderr).buffer.write(data)
                (sys.stdout if parts[2] == "1" else sys.stderr).flush()
            elif tag == "END" and len(parts) >= 3:
                code = int(parts[2])
                if code < 0:
                    code = 128 - code      # 被信号杀：对齐 shell 习惯 128+sig
                break
            elif tag == "ERR":
                sys.stderr.write("远端：" + " ".join(parts[1:]) + "\n")
                code = 124
                break
    except asyncio.TimeoutError:
        sys.stderr.write(f"超时（{timeout/1000:.0f}s），已要求远端终止\n")
        if jid:
            writer.write(f"KILL {jid}\n".encode())
            await writer.drain()
        code = 124
    except KeyboardInterrupt:
        if jid:
            try:
                writer.write(f"KILL {jid}\n".encode())
                await writer.drain()
            except OSError:
                pass
        code = 130
    finally:
        writer.close()
    return code


def main():
    try:
        rc = asyncio.run(run(sys.argv[1:]))
    except ConnectionRefusedError:
        sys.stderr.write(f"连不上中继 {HOST}:{PORT}（relay 没起？）\n")
        rc = 1
    except FileNotFoundError:
        sys.stderr.write(f"读不到 token 文件 {TOKEN_FILE}\n")
        rc = 1
    sys.exit(rc)


if __name__ == "__main__":
    main()
