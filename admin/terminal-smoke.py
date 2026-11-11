"""使用伪终端和隔离 HTTP 夹具验证实际 Linux 可执行程序；仅构建测试需要 Python。"""

import argparse
import errno
import fcntl
import json
import os
import pty
import re
import select
import struct
import subprocess
import tempfile
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    args = parser.parse_args()
    worlds = [{"worldId": "pty-world", "name": "PTY世界", "status": "FREE", "allowOfflinePlayers": False}]
    created = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def respond(self, body, status=200):
            payload = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            if self.path.endswith("/status"):
                self.respond({"version": "terminal-fixture", "uptimeMillis": 1000, "worlds": len(worlds), "ownedWorlds": 0, "users": 0, "onlineUsers": 0})
            else:
                self.respond({"worlds": worlds})

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path == "/api/v1/worlds/detail":
                self.respond({"world": next(world for world in worlds if world["worldId"] == request["worldId"]), "storage": {"currentBytes": 1024}})
                return
            if self.path == "/api/v1/worlds/backups":
                self.respond({"backups": [{"version": 1, "current": True}]})
                return
            if self.path != "/api/v1/worlds":
                self.respond({"code": "UNEXPECTED_PATH"}, 400)
                return
            if set(request) != {"name", "allowOfflinePlayers"} or type(request["allowOfflinePlayers"]) is not bool:
                self.respond({"code": "INVALID_WORLD"}, 400)
                return
            created.append(request)
            world = {"worldId": f"created-world-{len(created)}", "name": request["name"], "status": "FREE",
                     "allowOfflinePlayers": request["allowOfflinePlayers"]}
            worlds.append(world)
            self.respond(world, 201)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    executable = str(args.executable.resolve())
    port = server.server_port
    try:
        # 验证无需 TTY 的只读检查与实际伪终端的首页、中文输入、创建、刷新和正常退出。
        check = subprocess.run([executable, "--port", str(port), "--check"], capture_output=True, check=True, timeout=10)
        assert json.loads(check.stdout)["version"] == "terminal-fixture"
        with tempfile.TemporaryDirectory(prefix="admin-terminal-") as directory:
            master, slave = pty.openpty()
            # 较小高度确保概览溢出，让滚轮必须实际移动可见内容。
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 20, 100, 0, 0))
            env = dict(os.environ, TERM="xterm-256color", NO_COLOR="1", LANG="C.UTF-8")
            process = subprocess.Popen([executable, "--port", str(port), "--refresh", "0", "--state-dir", directory],
                                       stdin=slave, stdout=slave, stderr=slave, env=env)
            os.close(slave)
            captured = bytearray()

            def terminal_failure(message):
                recent = captured[-12000:].decode("utf-8", errors="replace")
                recent = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", recent)
                return RuntimeError(f"{message}; exit status: {process.poll()}; recent terminal output: {recent[-4000:]!r}")

            def expect(value, since=None):
                deadline = time.monotonic() + 10
                received = bytearray() if since is None else bytearray(captured[since:])
                while value.encode() not in received:
                    if time.monotonic() > deadline:
                        raise terminal_failure(f"Terminal did not display {value!r}")
                    if select.select([master], [], [], 0.1)[0]:
                        try:
                            block = os.read(master, 65536)
                        except OSError as failure:
                            if failure.errno != errno.EIO:
                                raise
                            raise terminal_failure(f"Terminal closed while waiting for {value!r}") from failure
                        if not block:
                            raise terminal_failure(f"Terminal closed while waiting for {value!r}")
                        received.extend(block)
                        captured.extend(block)

            try:
                expect("terminal-fixture")
                # 验证终端 SGR 滚轮事件确实改变视口，并且不会触发管理写入。
                os.write(master, b"\x1b[<65;6;5M" * 30)
                expect("累计发送")
                assert not created
                os.write(master, b"\x1b[<64;6;5M" * 30)
                expect("terminal-fixture")
                os.write(master, b"\r")
                expect("PTY世界")
                # 右侧滚轮独立滚动详情，空格切换焦点，AD 只切换操作按钮。
                os.write(master, b"\x1b[<65;60;5M" * 20)
                expect("#1")
                os.write(master, b" ")
                expect("▸刷新")
                os.write(master, b"d")
                expect("▸恢复备份")
                assert not created
                os.write(master, b" ")
                expect("▸创建世界")
                form_start = len(captured)
                os.write(master, b"\r")
                expect("创建共享世界")
                # 验证真实终端光标跟随输入框和中文编辑位置，供 SSH 客户端的输入法候选窗定位。
                def native_cursor():
                    positions = re.findall(rb"\x1b\[(\d+);(\d+)H\x1b\[\?25h", captured)
                    assert positions, "No native input cursor position was emitted"
                    return tuple(map(int, positions[-1]))

                expect("\x1b[?25h", form_start)
                input_row, input_column = native_cursor()
                assert input_column == 5 and input_row < 20
                typed_start = len(captured)
                os.write(master, "PTY新世界".encode())
                expect("PTY新世界")
                expect(f"\x1b[{input_row};14H\x1b[?25h", typed_start)
                left_start = len(captured)
                os.write(master, b"\x1b[D")
                expect(f"\x1b[{input_row};12H\x1b[?25h", left_start)
                right_start = len(captured)
                os.write(master, b"\x1b[C")
                expect(f"\x1b[{input_row};14H\x1b[?25h", right_start)
                # 名称提交后只进入模式选择；ESC 返回保留名称，方向键选择离线模式后才创建。
                mode_start = len(captured)
                os.write(master, b"\r")
                expect("登录模式", mode_start)
                expect("创建后不可修改", mode_start)
                expect("▸仅允许正版账号", mode_start)
                assert not created
                back_start = len(captured)
                os.write(master, b"\x1b")
                expect("输入说明", back_start)
                expect("PTY新世界", back_start)
                mode_start = len(captured)
                os.write(master, b"\r")
                expect("▸仅允许正版账号", mode_start)
                selected_start = len(captured)
                os.write(master, b"\x1b[C")
                expect("▸允许离线账号", selected_start)
                assert not created
                result_start = len(captured)
                os.write(master, b"\r")
                expect("操作成功", result_start)
                assert created == [{"name": "PTY新世界", "allowOfflinePlayers": True}]
                list_start = len(captured)
                os.write(master, b"\x1b")
                expect("PTY新世界", list_start)
                # 保留默认正版模式时，回车应明确提交 false，而不是依赖夹具补默认值。
                form_start = len(captured)
                os.write(master, b"\r")
                expect("输入说明", form_start)
                os.write(master, "PTY正版世界".encode())
                expect("PTY正版世界")
                mode_start = len(captured)
                os.write(master, b"\r")
                expect("▸仅允许正版账号", mode_start)
                result_start = len(captured)
                os.write(master, b"\r")
                expect("操作成功", result_start)
                assert created == [{"name": "PTY新世界", "allowOfflinePlayers": True},
                                   {"name": "PTY正版世界", "allowOfflinePlayers": False}]
                list_start = len(captured)
                os.write(master, b"\x1b")
                expect("PTY正版世界", list_start)
                os.write(master, b"\x1b")
                expect("服务概览")
                os.write(master, b"\x1b[F")
                expect("▸退出")
                os.write(master, b"\r")
                assert process.wait(timeout=10) == 0
                assert not (Path(directory) / "pending.json").exists()
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                os.close(master)
        print("Terminal smoke passed: read-only check, overview, mouse wheel, cursor navigation, Chinese input, fixed login modes, ESC return and menu exit")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == "__main__":
    main()
