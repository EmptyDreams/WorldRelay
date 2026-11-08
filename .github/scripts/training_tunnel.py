"""固定训练流程使用的环回隧道客户端；不参与模组或服务端发行包。"""

import base64
import hashlib
import os
import socket
import ssl
import struct
import threading

HELLO, REGISTERED, OPEN, READY, DATA, PING, PONG, ERROR, CLOSE = range(1, 10)
CHUNK = 65536


class Tunnel:
    def __init__(self, mode, port, grant):
        self.mode = mode
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=15)
        self.reader = None
        self.lock = threading.Lock()
        try:
            if mode == "tcp":
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE
                self.socket = context.wrap_socket(self.socket, server_hostname="localhost")
                self.certificate = self.socket.getpeercert(binary_form=True)
                if hashlib.sha256(self.certificate).hexdigest() != grant["fingerprint"]:
                    raise RuntimeError("Tunnel TLS fingerprint mismatch")
            self.reader = self.socket.makefile("rb")
            if mode == "wss":
                key = base64.b64encode(b"WorldRelay train").decode()
                self.socket.sendall((
                    f"GET {grant['path']} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
                ).encode())
                headers = b""
                while not headers.endswith(b"\r\n\r\n"):
                    line = self.reader.readline(8193)
                    if not line or len(headers) + len(line) > 8192:
                        raise RuntimeError("Invalid WebSocket upgrade response")
                    headers += line
                accept = base64.b64encode(hashlib.sha1(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
                ).digest())
                if not headers.startswith(b"HTTP/1.1 101 ") or accept not in headers:
                    raise RuntimeError("WebSocket upgrade failed")
        except BaseException:
            self.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        if self.reader is not None:
            self.reader.close()
        self.socket.close()

    def read_exact(self, size):
        data = self.reader.read(size)
        if len(data) != size:
            raise RuntimeError("Tunnel closed before expected frame")
        return data

    def websocket_frame(self, body, *, opcode=2, final=True):
        mask = os.urandom(4)
        size = len(body)
        header = bytes([(128 if final else 0) | opcode])
        if size < 126:
            header += bytes([128 | size])
        elif size <= 65535:
            header += bytes([128 | 126]) + struct.pack(">H", size)
        else:
            header += bytes([128 | 127]) + struct.pack(">Q", size)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(body))
        with self.lock:
            self.socket.sendall(header + mask + masked)

    def send(self, kind, body=b"", *, fragment=False):
        if isinstance(body, str):
            body = body.encode()
        payload = bytes([kind]) + body
        if len(payload) > CHUNK + 1:
            raise ValueError("Training frame exceeds protocol limit")
        if self.mode == "tcp":
            with self.lock:
                self.socket.sendall(struct.pack(">I", len(payload)) + payload)
        elif fragment:
            self.websocket_frame(payload[:3], final=False)
            self.websocket_frame(payload[3:], opcode=0)
        else:
            self.websocket_frame(payload)

    def receive(self, expected):
        while True:
            if self.mode == "tcp":
                size = struct.unpack(">I", self.read_exact(4))[0]
            else:
                first, second = self.read_exact(2)
                if first & 0x80 == 0 or second & 0x80:
                    raise RuntimeError("Unexpected server WebSocket framing")
                opcode = first & 15
                size = second & 127
                if size == 126:
                    size = struct.unpack(">H", self.read_exact(2))[0]
                elif size == 127:
                    size = struct.unpack(">Q", self.read_exact(8))[0]
                if opcode in (9, 10):
                    if size > 125:
                        raise RuntimeError("Oversized WebSocket control frame")
                    body = self.read_exact(size)
                    if opcode == 9:
                        self.websocket_frame(body, opcode=10)
                    continue
                if opcode != 2:
                    raise RuntimeError(f"Unexpected WebSocket opcode {opcode}")
            if not 1 <= size <= CHUNK + 1:
                raise RuntimeError("Unexpected tunnel frame length")
            payload = self.read_exact(size)
            kind, body = payload[0], payload[1:]
            if kind == PING:
                self.send(PONG)
                continue
            if kind == PONG and expected != PONG:
                continue
            if kind != expected:
                raise RuntimeError(f"Expected tunnel type {expected}, got {kind}")
            return body
