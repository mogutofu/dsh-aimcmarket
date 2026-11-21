#!/usr/bin/env python3
"""CI 用的极简 RCON 客户端（只依赖标准库，给 runServer 冒烟测试使）。

用法：
  python3 tools/ci_rcon.py wait <port> <password> <timeoutSeconds>
  python3 tools/ci_rcon.py cmd  <port> <password> <command...>
  python3 tools/ci_rcon.py stop <port> <password>

退出码：0 成功；非 0 失败（wait 超时 / 认证失败 / 连接失败）。
"""

import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0


def _pack(req_id: int, req_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _read_packet(sock: socket.socket):
    raw_len = _recv_exact(sock, 4)
    if not raw_len:
        raise ConnectionError("连接被关闭")
    (length,) = struct.unpack("<i", raw_len)
    if length < 10 or length > 4 * 1024 * 1024:
        raise ConnectionError("RCON 包长度异常: %d" % length)
    data = _recv_exact(sock, length)
    if not data or len(data) < 10:
        raise ConnectionError("RCON 包不完整")
    req_id, req_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", "replace")
    return req_id, req_type, body


def _recv_exact(sock: socket.socket, n: int):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 10.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.host = host
        self.port = port
        self._req_id = 1
        self._auth(password)

    def _auth(self, password: str):
        self.sock.sendall(_pack(1, SERVERDATA_AUTH, password))
        req_id, req_type, _ = _read_packet(self.sock)
        if req_type != SERVERDATA_AUTH_RESPONSE or req_id == -1:
            raise PermissionError("RCON 密码错误")
        # 有的实现会多回一个空包
        self.sock.settimeout(0.5)
        try:
            _read_packet(self.sock)
        except Exception:
            pass
        self.sock.settimeout(10.0)

    def command(self, cmd: str) -> str:
        self._req_id += 1
        self.sock.sendall(_pack(self._req_id, SERVERDATA_EXECCOMMAND, cmd))
        parts = []
        while True:
            req_id, req_type, body = _read_packet(self.sock)
            if req_type == SERVERDATA_RESPONSE_VALUE:
                if body == "":
                    break
                parts.append(body)
            elif req_type == SERVERDATA_AUTH_RESPONSE:
                break
        return "".join(parts)

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    mode = argv[1]
    if mode == "wait":
        port = int(argv[2])
        password = argv[3]
        timeout = float(argv[4]) if len(argv) > 4 else 300.0
        deadline = time.time() + timeout
        last_error = None
        while time.time() < deadline:
            try:
                conn = Rcon("127.0.0.1", port, password)
                conn.close()
                print("RCON 就绪")
                return 0
            except Exception as error:  # 服务端还没起，继续等
                last_error = error
                time.sleep(3)
        print("等待 RCON 超时: %s" % last_error)
        return 1
    port = int(argv[2])
    password = argv[3]
    if mode == "cmd":
        command = " ".join(argv[4:])
        conn = Rcon("127.0.0.1", port, password)
        try:
            print(conn.command(command))
        finally:
            conn.close()
        return 0
    if mode == "stop":
        conn = Rcon("127.0.0.1", port, password)
        try:
            conn.command("stop")
        finally:
            conn.close()
        return 0
    print("未知模式: %s" % mode)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
