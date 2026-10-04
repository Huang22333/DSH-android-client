#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH 手机调试中继（phone-relay）。

作用：手机侧（DSH 安卓壳 + Shizuku）**主动出站**连到本机，我（agent）通过
本机 CLI 下发 shell 命令，手机以 shell(uid 2000) 身份执行并把输出流回来。
等效于「远程版 rish / adb shell」，但手机不开任何监听端口。

协议（行式文本，\\n 结尾，字段空格分隔，负载 base64）：
  客户端 → relay : HELLO <token> <PHONE|CTRL>
  relay  → 客户端: OK | ERR <原因>
  relay  → PHONE : EXEC <id> <b64cmd> | KILL <id> | PING
  PHONE  → relay : OUT <id> <fd> <b64chunk> | END <id> <code> | PONG
  CTRL   → relay : RUN <b64cmd> [timeout_ms] | KILL <id> | STATUS
  relay  → CTRL : OK <id> | OUT <id> <fd> <b64> | END <id> <code> | OK phones=<n>

安全：预共享 token（600 权限文件），明文 TCP，只放在受控网络内用；
要走公网必须套 TLS。
"""
import asyncio
import base64
import os
import sys
import time

DEFAULT_PORT = 2900
TOKEN_FILE = os.environ.get("PHONE_RELAY_TOKEN_FILE",
                            "/etc/dsh-relay/token")
MAX_CHUNK = 16 * 1024          # 单条 OUT 的原始字节上限
KEEPALIVE = 30.0               # 对 PHONE 的 PING 间隔
PHONE_DEAD = 90.0              # 超过这个秒数没消息就当掉线

_b64e = lambda b: base64.b64encode(b).decode("ascii")
_b64d = lambda s: base64.b64decode(s.encode("ascii"))


def load_token():
    tok = os.environ.get("PHONE_RELAY_TOKEN")
    if not tok:
        try:
            with open(TOKEN_FILE) as f:
                tok = f.read().strip()
        except OSError:
            sys.exit(f"读不到 token：{TOKEN_FILE}（或设 PHONE_RELAY_TOKEN）")
    if len(tok) < 16:
        sys.exit("token 太短（至少 16 字符）")
    return tok


class Phone:
    """一个已连接的手机。"""

    def __init__(self, writer, name):
        self.writer = writer
        self.name = name
        self.last_seen = time.time()
        self.jobs = {}          # id -> (ctrl_writer, started_at)


class Relay:
    def __init__(self, token):
        self.token = token
        self.phones = []        # 最新在末尾
        self.next_id = 1

    async def handle(self, reader, writer):
        peer = "%s:%d" % writer.get_extra_info("peername")[:2]
        try:
            line = await asyncio.wait_for(reader.readline(), 15)
        except (asyncio.TimeoutError, ConnectionError):
            writer.close()
            return
        parts = line.decode("utf-8", "replace").split()
        if len(parts) < 3 or parts[0] != "HELLO":
            print(f"[{time.strftime('%H:%M:%S')}] 拒绝 {peer}：握手行异常 "
                  f"{line.decode('utf-8', 'replace').strip()[:60]!r}", flush=True)
            writer.close()
            return
        _, tok, role = parts[0], parts[1], parts[2]
        if tok != self.token:
            print(f"[{time.strftime('%H:%M:%S')}] 拒绝 {peer}：token 不对（role={role}）", flush=True)
            writer.write(b"ERR bad-token\n")
            await writer.drain()
            writer.close()
            return
        if role == "PHONE":
            name = parts[3] if len(parts) > 3 else peer
            writer.write(b"OK\n")
            await writer.drain()
            await self.phone_loop(reader, writer, name, peer)
        elif role == "CTRL":
            writer.write(b"OK\n")
            await writer.drain()
            await self.ctrl_loop(reader, writer, peer)
        else:
            writer.write(b"ERR bad-role\n")
            await writer.drain()
            writer.close()

    # ---------------- PHONE 侧 ----------------
    async def phone_loop(self, reader, writer, name, peer):
        phone = Phone(writer, name)
        self.phones.append(phone)
        print(f"[{time.strftime('%H:%M:%S')}] 手机上线 {name} ({peer})", flush=True)
        try:
            while True:
                try:
                    line = await asyncio.wait_for(reader.readline(), KEEPALIVE)
                except asyncio.TimeoutError:
                    # 心跳
                    if time.time() - phone.last_seen > PHONE_DEAD:
                        raise ConnectionError("心跳超时")
                    writer.write(b"PING\n")
                    await writer.drain()
                    continue
                if not line:
                    raise ConnectionError("对端关闭")
                phone.last_seen = time.time()
                await self.from_phone(phone, line.decode("utf-8", "replace").strip())
        except (ConnectionError, asyncio.IncompleteReadError, OSError) as e:
            print(f"[{time.strftime('%H:%M:%S')}] 手机离线 {name}: {e}", flush=True)
        finally:
            if phone in self.phones:
                self.phones.remove(phone)
            # 告诉挂着的 CTRL：手机没了
            for _, (ctrl, _) in list(phone.jobs.items()):
                try:
                    ctrl.write(f"ERR phone-gone {phone.name}\n".encode())
                    await ctrl.drain()
                except OSError:
                    pass
            writer.close()

    async def from_phone(self, phone, line):
        parts = line.split(" ", 3)
        if not parts:
            return
        if parts[0] == "PONG":
            return
        if parts[0] in ("OUT", "END") and len(parts) >= 3:
            jid = parts[1]
            job = phone.jobs.get(jid)
            if job is None:
                return
            ctrl = job[0]
            try:
                if parts[0] == "OUT" and len(parts) == 4:
                    ctrl.write(f"OUT {jid} {parts[2]} {parts[3]}\n".encode())
                elif parts[0] == "END":
                    ctrl.write(f"END {jid} {parts[2]}\n".encode())
                    phone.jobs.pop(jid, None)
                await ctrl.drain()
            except OSError:
                phone.jobs.pop(jid, None)

    # ---------------- CTRL 侧 ----------------
    async def ctrl_loop(self, reader, writer, peer):
        print(f"[{time.strftime('%H:%M:%S')}] 控制端接入 {peer}", flush=True)
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                parts = line.decode("utf-8", "replace").strip().split(" ")
                cmd = parts[0] if parts else ""
                if cmd == "STATUS":
                    n = len(self.phones)
                    names = ",".join(p.name for p in self.phones) or "-"
                    writer.write(f"OK phones={n} [{names}]\n".encode())
                    await writer.drain()
                elif cmd == "RUN" and len(parts) >= 2:
                    await self.run_job(writer, parts[1],
                                       int(parts[2]) if len(parts) > 2 else 0)
                elif cmd == "KILL" and len(parts) >= 2:
                    await self.kill(parts[1])
                    writer.write(b"OK\n")
                    await writer.drain()
                else:
                    writer.write(b"ERR bad-command\n")
                    await writer.drain()
        except (ConnectionError, OSError):
            pass
        finally:
            writer.close()

    async def run_job(self, ctrl, b64cmd, timeout_ms):
        if not self.phones:
            ctrl.write(b"ERR no-phone\n")
            await ctrl.drain()
            return
        phone = self.phones[-1]
        jid = str(self.next_id)
        self.next_id += 1
        phone.jobs[jid] = (ctrl, time.time())
        ctrl.write(f"OK {jid}\n".encode())
        await ctrl.drain()
        phone.writer.write(f"EXEC {jid} {b64cmd}\n".encode())
        await phone.writer.drain()
        if timeout_ms > 0:
            async def watchdog():
                await asyncio.sleep(timeout_ms / 1000.0)
                if jid in phone.jobs:
                    await self.kill(jid)
            asyncio.get_event_loop().create_task(watchdog())

    async def kill(self, jid):
        for phone in self.phones:
            if jid in phone.jobs:
                try:
                    phone.writer.write(f"KILL {jid}\n".encode())
                    await phone.writer.drain()
                except OSError:
                    pass
                return


async def main():
    token = load_token()
    port = int(os.environ.get("PHONE_RELAY_PORT", DEFAULT_PORT))
    relay = Relay(token)
    server = await asyncio.start_server(relay.handle, "0.0.0.0", port)
    print(f"phone-relay 就绪 0.0.0.0:{port}（token 来自 {TOKEN_FILE}）", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
