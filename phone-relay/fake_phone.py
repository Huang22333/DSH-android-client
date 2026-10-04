#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""假手机：本地跑通整条中继链路的测试替身。

和 App 侧 DebugRelayService 用同一套协议，只是把「Shizuku shell 执行」
换成本机 subprocess —— 用于在真机上线之前验证 relay + CLI 的端到端行为。
"""
import asyncio
import base64
import os
import socket
import ssl
import subprocess
import sys
import time

HOST = os.environ.get("PHONE_RELAY_HOST", "127.0.0.1")
PORT = int(os.environ.get("PHONE_RELAY_PORT", "2900"))
TOKEN_FILE = os.environ.get("PHONE_RELAY_TOKEN_FILE",
                            "/etc/dsh-relay/token")
NAME = os.environ.get("FAKE_PHONE_NAME", socket.gethostname())
CHUNK = 16 * 1024
# FAKE_PHONE_TLS=1 时走 TLS（模拟 App 经 relay.example.com:443 的公网链路）
USE_TLS = os.environ.get("FAKE_PHONE_TLS") == "1"

jobs = {}


def load_token():
    return os.environ.get("PHONE_RELAY_TOKEN") or open(TOKEN_FILE).read().strip()


async def pump(reader, writer, jid, fd):
    """把子进程一条输出流切成 base64 块发回。"""
    try:
        while True:
            data = await reader.read(CHUNK)
            if not data:
                break
            writer.write(("OUT %s %d %s\n" % (
                jid, fd, base64.b64encode(data).decode())).encode())
            await writer.drain()
    except (ConnectionError, OSError):
        pass


async def exec_job(writer, jid, cmd):
    proc = await asyncio.create_subprocess_exec(
        "/bin/sh", "-c", cmd,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        start_new_session=True)   # 独立进程组，KILL 时能连孙子一起杀
    jobs[jid] = proc
    await asyncio.gather(pump(proc.stdout, writer, jid, 1),
                         pump(proc.stderr, writer, jid, 2))
    code = await proc.wait()
    jobs.pop(jid, None)
    writer.write(("END %s %d\n" % (jid, code)).encode())
    await writer.drain()


async def main():
    token = load_token()
    while True:
        try:
            if USE_TLS:
                ctx = ssl.create_default_context()   # 系统信任库 + 主机名校验（与 App 一致）
                reader, writer = await asyncio.open_connection(
                    HOST, PORT, ssl=ctx, server_hostname=HOST)
            else:
                reader, writer = await asyncio.open_connection(HOST, PORT)
            writer.write(("HELLO %s PHONE %s\n" % (token, NAME)).encode())
            await writer.drain()
            ack = await asyncio.wait_for(reader.readline(), 10)
            if ack.strip() != b"OK":
                print("中继拒绝：", ack.decode(), flush=True)
                return 1
            print(f"假手机 {NAME} 已上线 {HOST}:{PORT}", flush=True)
            while True:
                line = await reader.readline()
                if not line:
                    raise ConnectionError("中继关闭")
                parts = line.decode("utf-8", "replace").strip().split(" ", 2)
                if parts[0] == "EXEC" and len(parts) == 3:
                    cmd = base64.b64decode(parts[2].encode()).decode()
                    asyncio.get_event_loop().create_task(
                        exec_job(writer, parts[1], cmd))
                elif parts[0] == "KILL" and len(parts) >= 2:
                    proc = jobs.get(parts[1])
                    if proc:
                        try:
                            os.killpg(os.getpgid(proc.pid), 9)   # 杀整组，别留孤儿
                        except OSError:
                            proc.kill()
                elif parts[0] == "PING":
                    writer.write(b"PONG\n")
                    await writer.drain()
        except (ConnectionError, OSError) as e:
            print(f"掉线（{e}），3s 后重连…", flush=True)
            await asyncio.sleep(3)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
