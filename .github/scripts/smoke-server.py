"""验证可执行服务的 HTTP、持久化、WebSocket 和 TCP TLS 启动路径。"""

import argparse
import base64
import hashlib
import json
import os
import re
import signal
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


def request(port, path, *, body=None, authorization=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if authorization:
        headers["Authorization"] = authorization
    data = None if body is None else json.dumps(body).encode()
    query = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data, headers)
    try:
        response = urllib.request.urlopen(query, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        if response.status != expected:
            raise RuntimeError(f"{path}: expected HTTP {expected}, got {response.status}")
        payload = response.read()
        return json.loads(payload) if payload else None


def websocket(port):
    key = base64.b64encode(b"WorldRelay smoke").decode()
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.sendall((
            f"GET /api/v1/tunnel HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        ).encode())
        response = b""
        while b"\r\n\r\n" not in response:
            block = connection.recv(4096)
            if not block or len(response) > 8192:
                raise RuntimeError("Incomplete WebSocket upgrade response")
            response += block
        accept = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
        ).digest()).decode()
        if not response.startswith(b"HTTP/1.1 101 ") or accept.encode() not in response:
            raise RuntimeError("WebSocket upgrade failed")


def stop_server(process, log):
    if process.poll() is not None:
        raise RuntimeError(f"Server exited before requested shutdown with {process.returncode}; see {log}")
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        raise RuntimeError(f"Server did not shut down; see {log}")
    # Python 直接观察到 SIGTERM 时返回负值；Java/GraalVM 的信号处理器也可能返回 128 + SIGTERM。
    # 只在主动 terminate() 后接受这些结果；Windows terminate() 使用强制终止。
    if os.name == "posix" and process.returncode not in (0, -signal.SIGTERM, 128 + signal.SIGTERM):
        raise RuntimeError(f"Server exited with {process.returncode}; see {log}")


def run(command, directory, mode, attempt, authorization, world_id):
    config = directory / "server.properties"
    config.write_text(
        "http.bind-address=127.0.0.1\nhttp.port=0\nregistration.port=0\n"
        "http.shutdown-grace-seconds=0\nauth.users-file=data/users.json\n"
        "worlds.directory=data/worlds\n"
        f"tunnel.mode={mode}\ntunnel.bind-address=127.0.0.1\ntunnel.port=0\n"
        "tunnel.public-host=127.0.0.1\ntunnel.public-port=2002\n",
        encoding="utf-8",
    )
    log = directory / f"{attempt}-{mode}.log"
    with log.open("wb") as output:
        process = subprocess.Popen(command + ["--config", str(config)], cwd=directory,
                                   stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                logs = log.read_text(encoding="utf-8", errors="replace")
                ports = re.findall(r"WorldRelay (?:HTTP|registration|tunnel \(\w+\)) listening on /127\.0\.0\.1:(\d+)", logs)
                if len(ports) == 3:
                    break
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"Server did not start; see {log}")
                time.sleep(0.1)
            api, admin, tunnel = map(int, ports)
            request(api, "/api/v1/worlds", expected=401)
            if authorization is None:
                user = request(admin, "/api/v1/register", body={"username": "NativeSmoke"}, expected=201)
                authorization = "Basic " + base64.b64encode(f"NativeSmoke:{user['token']}".encode()).decode()
                world = request(admin, "/api/v1/worlds", body={"name": "Native smoke 世界"}, expected=201)
                world_id = world["worldId"]
            worlds = request(api, "/api/v1/worlds", authorization=authorization)
            if not any(world["worldId"] == world_id for world in worlds["worlds"]):
                raise RuntimeError("World or credentials did not survive restart")
            if mode == "wss":
                websocket(tunnel)
                certificate = None
            else:
                # 仅验证本次启动自行生成的证书；生产客户端仍按 API 返回的指纹校验。
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE
                with socket.create_connection(("127.0.0.1", tunnel), timeout=5) as connection:
                    with context.wrap_socket(connection, server_hostname="localhost") as secure:
                        certificate = secure.getpeercert(binary_form=True)
        finally:
            stop_server(process, log)
    print(f"{mode} startup {attempt}: HTTP, authentication, storage, tunnel and shutdown passed", flush=True)
    return authorization, world_id, certificate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("Expected server executable after --")
    command[0] = str(Path(command[0]).resolve())
    directory = args.work_dir.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    # 验证两种隧道启动、注册与世界重启持久化，以及首次生成和重启加载同一 TLS 证书。
    authorization, world_id, _ = run(command, directory, "wss", 1, None, None)
    authorization, world_id, certificate = run(command, directory, "tcp", 2, authorization, world_id)
    _, _, reloaded = run(command, directory, "tcp", 3, authorization, world_id)
    if certificate != reloaded:
        raise RuntimeError("TLS certificate changed after restart")


if __name__ == "__main__":
    main()
