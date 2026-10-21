#!/usr/bin/env python3
"""极简 RCON 客户端：手册 2.6 的 mcrcon 替代品（Homebrew 里没有 mcrcon）。

用法（在 Mac mini 上）：
    python3 ~/mcserver/rcon.py "whitelist list"
    python3 ~/mcserver/rcon.py "whitelist add Zan421" "whitelist add Blockmate" "whitelist list"

密码：先读环境变量 RCON_PASSWORD；没有就在终端里问你，输入时不回显。
地址和端口：默认读同一文件夹里 server.properties 的 server-ip 和 rcon.port（服务器设了 server-ip 之后
RCON 也只听那个地址，连 127.0.0.1 会被拒），没有就用 127.0.0.1:25575；环境变量 RCON_HOST / RCON_PORT 可覆盖。
只用 Python 标准库，macOS 自带的 python3 就能跑。
"""
import getpass
import os
import socket
import struct
import sys

AUTH = 3      # 登录包
COMMAND = 2   # 命令包


def send(sock, packet_id, packet_type, body):
    data = struct.pack("<ii", packet_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def recv(sock):
    header = recv_exact(sock, 4)
    (length,) = struct.unpack("<i", header)
    data = recv_exact(sock, length)
    packet_id, packet_type = struct.unpack("<ii", data[:8])
    return packet_id, packet_type, data[8:-2].decode("utf-8", "replace")


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("连接被服务器关闭")
        buf += chunk
    return buf


def defaults_from_server_properties():
    """从脚本旁边的 server.properties 读 server-ip 和 rcon.port；读不到就用 127.0.0.1:25575。"""
    host, port = "127.0.0.1", 25575
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.properties")
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                key, sep, value = line.strip().partition("=")
                if sep and key == "server-ip" and value:
                    host = value
                elif sep and key == "rcon.port" and value.isdigit():
                    port = int(value)
    except OSError:
        pass
    return host, port


def main(argv):
    commands = argv[1:]
    if not commands:
        print(__doc__)
        return 2
    default_host, default_port = defaults_from_server_properties()
    host = os.environ.get("RCON_HOST", default_host)
    port = int(os.environ.get("RCON_PORT", default_port))
    password = os.environ.get("RCON_PASSWORD") or getpass.getpass("RCON 密码（输入不回显）: ")
    try:
        with socket.create_connection((host, port), timeout=10) as sock:
            send(sock, 1, AUTH, password)
            packet_id, _, _ = recv(sock)
            if packet_id == -1:
                print("RCON 密码错误", file=sys.stderr)
                return 1
            for i, command in enumerate(commands, start=2):
                send(sock, i, COMMAND, command)
                _, _, body = recv(sock)
                print(f"> {command}")
                print(body if body else "(无输出)")
    except (OSError, ConnectionError) as e:
        print(f"连不上 {host}:{port}：{e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
