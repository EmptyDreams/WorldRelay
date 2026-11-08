"""在独立空目录运行固定的黑盒业务流程，用于 PGO 训练和优化后验收。"""

import argparse
import base64
import hashlib
import importlib.util
import json
import os
import struct
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from training_tunnel import CLOSE, DATA, ERROR, HELLO, OPEN, PING, PONG, READY, REGISTERED, Tunnel

spec = importlib.util.spec_from_file_location("smoke_server", Path(__file__).with_name("smoke-server.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def manifest_bytes(world):
    # 测试数据的路径只用 BMP 字符，其 UTF-8 与 Java writeUTF 的编码一致。
    entries = sorted(world.rglob("*"), key=lambda path: path.relative_to(world).as_posix())
    result = bytearray(struct.pack(">II", 0x57525332, len(entries)))
    for path in entries:
        name = path.relative_to(world).as_posix().encode()
        directory = path.is_dir()
        data = b"" if directory else path.read_bytes()
        digest = b"" if directory else hashlib.sha256(data).hexdigest().encode()
        result += struct.pack(">H", len(name)) + name
        result += struct.pack(">?QH", directory, len(data), len(digest)) + digest
    return bytes(result)


class Workload:
    def __init__(self, root):
        self.root = root
        self.users = {}
        self.sequence = 0
        self.requests = {}
        self.scenarios = []
        self.forwarded = 0
        self.metrics_lock = threading.Lock()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def uid(self):
        self.sequence += 1
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"worldrelay-pgo/{self.sequence}"))

    def mark(self, label):
        self.scenarios.append(label)
        print(f"Training: {label}", flush=True)

    def call(self, path, body=None, *, admin=False, user="Host", expected=200,
             method=None, raw=None, headers=None, poll=False, code=None):
        port = self.admin if admin else self.api
        request_headers = {"Content-Type": "application/json"}
        if not admin and user is not None:
            request_headers["Authorization"] = self.users.get(user, "Basic invalid")
        request_headers.update(headers or {})
        data = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False).encode()
        query = urllib.request.Request(f"http://127.0.0.1:{port}/api/v1/{path}", data,
                                       request_headers, method=method)
        deadline = time.monotonic() + 45
        while True:
            try:
                response = self.opener.open(query, timeout=15)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.status
                payload = response.read()
                result = json.loads(payload) if payload else None
                require(response.headers.get("X-Request-Id"), "Missing request ID")
            key = f"{'admin' if admin else 'api'} {query.get_method()} {path} {status}"
            with self.metrics_lock:
                self.requests[key] = self.requests.get(key, 0) + 1
            if status == 202 and poll:
                require(time.monotonic() < deadline, f"Background task timed out: {path}")
                time.sleep(0.01)
                continue
            allowed = (expected,) if isinstance(expected, int) else expected
            require(status in allowed, f"{path}: expected {expected}, got {status}, code={(result or {}).get('code')}")
            if code is not None:
                require(result["code"] == code, f"Unexpected error code for {path}")
            return result

    def action(self, name, body=None, **options):
        return self.call("worlds/" + name, body, **options)

    def register(self, name, *, overwrite=False):
        response = self.call("register", {"username": name, "overwrite": overwrite},
                             admin=True, expected=200 if overwrite else 201)
        self.users[name] = "Basic " + base64.b64encode(f"{name}:{response['token']}".encode()).decode()

    def create(self, name):
        return self.call("worlds", {"name": name}, admin=True, expected=201)["worldId"]

    def acquire(self, world, user="Host", *, extra=None):
        request = {"worldId": world, "deviceId": self.uid(), "requestId": self.uid()}
        request.update(extra or {})
        view = self.action("acquire", request, user=user, expected=201)
        owner = {"worldId": world, "deviceId": request["deviceId"], "sessionId": view["sessionId"], "epoch": view["epoch"]}
        return owner, view, request

    def worlds(self):
        return {world["worldId"]: world for world in self.call("worlds")["worlds"]}

    def transfer(self, owner, user, base, *, upload):
        command = dict(owner, baseVersion=base, uploadId=self.uid())
        ticket = self.action("upload" if upload else "download", command, user=user, poll=True)
        require(ticket["privateKey"].startswith("-----BEGIN OPENSSH PRIVATE KEY-----"), "Missing temporary SSH key")
        username_hash = hashlib.sha256(user.encode()).hexdigest()
        path = self.root / "data/transfers/.sessions" / username_hash / owner["worldId"] / str(owner["epoch"])
        path = path / (("upload-" if upload else "download-") + command["uploadId"]) / "payload"
        require(path.resolve().is_relative_to(self.root), "Transfer fixture escaped training directory")
        if os.name == "nt":
            path = Path("\\\\?\\" + str(path))
        require(path.is_dir(), "Transfer preparation did not create payload")
        return command, ticket, path

    def download(self, owner, user, base):
        command, ticket, payload = self.transfer(owner, user, base, upload=False)
        manifest = (payload / "manifest.bin").read_bytes()
        require(hashlib.sha256(manifest).hexdigest() == ticket["manifestHash"], "Download manifest hash differs")
        require(manifest_bytes(payload / "world") == manifest, "Download files differ from authoritative manifest")
        self.action("close-download", command, user=user)
        self.action("close-download", command, user=user)

    def commit(self, owner, user, base, revision, *, corrupt=False):
        self.action("stop", dict(owner, stage="BEGIN"), user=user)
        self.action("stop", dict(owner, stage="UPLOAD_COMPLETE", baseVersion=base), user=user)
        command, original_ticket, payload = self.transfer(owner, user, base, upload=True)
        world = payload / "world"
        files = {
            "level.dat": f"synthetic-training-level-{revision}".encode(),
            "data/地图.dat": bytes(range(256)) * (1024 + revision),
            "region/r.0.0.mca": bytes(range(255, -1, -1)) * 2048,
            "DIM-1/region/r.0.0.mca": b"nether" * 4096,
            "dimensions/training/moon/data/state.dat": b"mod-persistent-data" * 1024,
            "serverconfig/training.toml": b"enabled=true\n",
            "playerdata/" + self.uid() + ".dat": b"player-inventory-and-attached-data",
            "advancements/progress.json": b'{"training":{"done":true}}',
            "zero.bin": b"",
        }
        for name, data in files.items():
            target = world / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        (world / "empty-directory").mkdir(exist_ok=True)
        obsolete = world / "deleted-next-version.txt"
        if revision == 1:
            obsolete.write_bytes(b"delete this in the next snapshot")
        else:
            obsolete.unlink(missing_ok=True)
        manifest = manifest_bytes(world)
        (payload / "manifest.bin").write_bytes(manifest)
        command["manifestHash"] = hashlib.sha256(manifest).hexdigest()
        require(self.action("commit-status", command, user=user)["version"] is None, "Uncommitted upload has a receipt")
        if corrupt:
            self.call("users/disable", {"username": user}, admin=True)
            self.action("heartbeat", owner, user=user, expected=401)
            detail = self.action("detail", {"worldId": owner["worldId"]}, admin=True)
            require(detail["world"]["epoch"] == owner["epoch"], "Disabling user changed ownership")
            self.call("users/enable", {"username": user}, admin=True)
            renewed = self.action("upload", {key: value for key, value in command.items() if key != "manifestHash"}, user=user, poll=True)
            require(renewed["privateKey"] != original_ticket["privateKey"], "Disabled transfer authorization was reused")
            self.action("commit", dict(command, manifestHash="0" * 64), user=user, expected=503, poll=True)
            malformed = b"invalid-manifest-format"
            (payload / "manifest.bin").write_bytes(malformed)
            self.action("commit", dict(command, manifestHash=hashlib.sha256(malformed).hexdigest()),
                        user=user, expected=503, poll=True)
            (payload / "manifest.bin").write_bytes(manifest)
            (world / "level.dat").write_bytes(b"corrupted-after-manifest")
            self.action("commit", command, user=user, expected=503, poll=True, code="SNAPSHOT_TRANSFER_FAILED")
            require(self.action("heartbeat", owner, user=user)["phase"] == "COMMITTING", "Failed upload released ownership")
            self.action("acquire", {"worldId": owner["worldId"], "deviceId": self.uid(), "requestId": self.uid()},
                        user="Guest", expected=409)
            (world / "level.dat").write_bytes(files["level.dat"])
            extra = world / "unlisted.bin"
            extra.write_bytes(b"not-in-the-manifest")
            self.action("commit", command, user=user, expected=503, poll=True)
            extra.unlink()
        result = self.action("commit", command, user=user, poll=True)
        version = 1 if base is None else base + 1
        require(result["version"] == version, "Commit did not publish the next version")
        require(self.action("commit", command, user=user, poll=True) == result, "Commit retry differs")
        require(self.action("commit-status", command, user=user)["version"] == version, "Commit receipt missing")
        self.action("commit-status", dict(command, manifestHash="0" * 64), user=user, expected=409)
        require(self.worlds()[owner["worldId"]]["status"] == "FREE", "Successful commit did not release ownership")
        return command

    def abandon(self, world):
        preview = self.action("abandon-preview", {"worldId": world}, admin=True)
        self.action("abandon", preview, admin=True, expected=400, code="ABANDON_CONFIRMATION_REQUIRED")
        confirmed = dict(preview, reason="PGO isolated fixture", confirm=True)
        result = self.action("abandon", confirmed, admin=True)
        require(self.action("abandon", confirmed, admin=True) == result, "Abandonment retry differs")
        return result

    def tunnel_flow(self, owner, user, base):
        # 验证真实隧道登记、票据一次性、双向字节顺序、心跳及主动关闭，不启动 Minecraft。
        self.action("ready", dict(owner, stage="SAVE_SYNCED", baseVersion=base), user=user)
        grant = self.action("tunnel-ticket", dict(owner, role="host"), user=user)
        with Tunnel(self.mode, self.port, grant) as control:
            control.send(HELLO, "1\n" + grant["ticket"], fragment=self.mode == "wss")
            registration = control.receive(REGISTERED).decode()
            ready = dict(owner, stage="PORT_OPENED", tunnelId=registration)
            require(self.action("ready", ready, user=user)["phase"] == "RUNNING", "Host was not published")
            self.action("ready", ready, user=user)
            require(self.worlds()[owner["worldId"]]["endpoint"]["address"] == owner["worldId"] + ".localhost", "Virtual hostname differs")
            if self.mode == "wss":
                control.websocket_frame(b"training-control-ping", opcode=9)
            control.send(PING)
            require(control.receive(PONG) == b"", "Invalid tunnel pong")
            with Tunnel(self.mode, self.port, grant) as replay:
                replay.send(HELLO, "1\n" + grant["ticket"])
                require(replay.receive(ERROR) == b"TUNNEL_REJECTED", "Replayed ticket was accepted")
            with Tunnel(self.mode, self.port, grant) as invalid:
                invalid.send(HELLO, "invalid-hello")
                invalid.receive(ERROR)
            guest_user = "Guest" if user == "Host" else "Host"
            for consent in (False, True):
                ticket = self.action("tunnel-ticket", {"worldId": owner["worldId"], "role": "join", "allowNewPlayer": consent}, user=guest_user)
                with Tunnel(self.mode, self.port, ticket) as guest:
                    guest.send(HELLO, "1\n" + ticket["ticket"])
                    token, username, allowed = control.receive(OPEN).decode().split("\n")
                    require(username == guest_user and allowed == str(int(consent)), "Guest identity or consent changed")
                    with Tunnel(self.mode, self.port, grant) as attached:
                        attached.send(HELLO, "1\n" + token)
                        attached.receive(READY)
                        guest.receive(READY)
                        samples = [b"", b"x", bytes(range(125)), bytes(range(126)), bytes(range(256)) * 16]
                        samples += [bytes(range(256)) * 255 + bytes(range(255)), bytes(range(256)) * 256]
                        samples += [bytes(range(256)) * 256] * 32
                        with ThreadPoolExecutor(max_workers=1) as jobs:
                            for source, destination in ((guest, attached), (attached, guest)):
                                def send_all():
                                    for sample in samples:
                                        source.send(DATA, sample)
                                sending = jobs.submit(send_all)
                                for sample in samples:
                                    require(destination.receive(DATA) == sample, "Tunnel bytes changed or reordered")
                                    self.forwarded += len(sample)
                                sending.result(timeout=15)
                        self.action("heartbeat", dict(owner, playerCount=2), user=user)
                        guest.send(CLOSE)
                        attached.receive(CLOSE)
            self.action("heartbeat", dict(owner, playerCount=0), user=user)
        self.mark(self.mode + ": tunnel forwarding, ticket rejection and close")
        return getattr(control, "certificate", None)

    def protocol_errors(self):
        # 验证身份、HTTP 路由、JSON、体积限制和管理接口隔离的常见拒绝路径。
        self.call("worlds", user=None, expected=401)
        self.call("worlds", user="unknown", expected=401)
        self.call("ping", expected=204)
        self.call("ping", {}, expected=405)
        self.call("missing", expected=404)
        self.call("register", {"username": "Forbidden"}, expected=404)
        self.call("ping", admin=True, expected=404)
        self.action("acquire", raw=b"{", expected=400)
        self.action("acquire", {}, headers={"Content-Type": "text/plain"}, expected=415)
        self.action("acquire", raw=b"x" * 4097, expected=413)
        for action in ("heartbeat", "ready", "stop", "cancel-creation", "check-local-reuse", "tunnel-ticket",
                       "download", "upload", "commit", "commit-status", "close-download"):
            self.action(action, {}, expected=400)
        for action in ("backups", "restore-preview", "restore", "abandon-preview", "abandon"):
            self.action(action, {}, admin=True, expected=400)
        self.mark(self.mode + ": authentication, protocol errors and management isolation")

    def management(self):
        # 验证五类管理接口：查询与统计、用户禁用持久化、改名、确认删除以及错误请求。
        status = self.call("status", admin=True)
        require(status["version"] != "unknown", "Build version resource is missing")
        if status["nativeImage"]:
            require(0 < status["heap"]["maxBytes"] <= 512 * 1024 * 1024, "Native runtime heap cap is not 512 MiB")
        accounts = {item["username"]: item for item in self.call("users", admin=True)["users"]}
        require(not accounts["Disabled"]["enabled"], "Disabled account was not retained across restart")
        self.call("worlds", user="Disabled", expected=401)
        self.call("users/enable", {"username": "missing"}, admin=True, expected=404)
        for path in ("status", "users"):
            self.call(path, expected=404)
        for action in ("detail", "history", "rename", "delete-preview", "delete"):
            self.action(action, {}, admin=True, expected=400)
        world = self.create(self.mode + " management")
        require(any(item["worldId"] == world for item in self.call("worlds", admin=True)["worlds"]), "Management list omitted world")
        self.action("detail", {"worldId": world}, admin=True)
        preview = self.action("delete-preview", {"worldId": world}, admin=True)
        renamed = self.action("rename", {"worldId": world, "expectedName": self.mode + " management", "name": self.mode + " renamed"}, admin=True)
        require(renamed["world"]["worldId"] == world, "Rename changed world identity")
        self.action("delete", dict(preview, confirm=True), admin=True, expected=409)
        preview = self.action("delete-preview", {"worldId": world}, admin=True)
        self.action("delete", preview, admin=True, expected=400)
        confirmed = dict(preview, confirm=True)
        deleted = self.action("delete", confirmed, admin=True)
        require(self.action("delete", confirmed, admin=True) == deleted, "Deletion retry differs")
        self.action("detail", {"worldId": world}, admin=True, expected=404)
        self.mark(self.mode + ": management queries, user status, rename and confirmed deletion")

    def scenario(self):
        self.protocol_errors()
        name = self.mode + " PGO 世界"
        world = self.create(name)
        self.call("worlds", {"name": name}, admin=True, expected=409)
        self.call("worlds", {"name": ""}, admin=True, expected=400)
        self.action("backups", {"worldId": self.uid()}, admin=True, expected=404)
        self.action("acquire", {"worldId": self.uid(), "deviceId": self.uid(), "requestId": self.uid()}, expected=404)
        # 两个已注册用户并发获取同一新世界，必须恰好一人取得任期。
        race_world = self.create(self.mode + " concurrent acquire")
        contenders = [(user, {"worldId": race_world, "deviceId": self.uid(), "requestId": self.uid()})
                      for user in ("Host", "Guest")]
        def compete(contender):
            user, request = contender
            return self.action("acquire", request, user=user, expected=(201, 409))
        with ThreadPoolExecutor(max_workers=2) as jobs:
            outcomes = list(jobs.map(compete, contenders))
        require(sum("sessionId" in result for result in outcomes) == 1, "Concurrent acquire did not have exactly one winner")
        require(sum(result.get("code") == "WORLD_OWNED" for result in outcomes) == 1, "Concurrent acquire loser was not fenced")
        self.abandon(race_world)
        owner, view, acquisition = self.acquire(world)
        require(not view["hasSave"] and view["baseVersion"] is None, "New world unexpectedly has a snapshot")
        require(self.action("acquire", acquisition)["sessionId"] == owner["sessionId"], "Acquire retry changed ownership")
        self.action("heartbeat", owner, user="Guest", expected=403)
        self.action("ready", dict(owner, stage="SAVE_SYNCED", baseVersion=123), expected=409)
        self.action("download", dict(owner, baseVersion=None, uploadId=self.uid()), expected=409, poll=True, code="NO_SNAPSHOT")
        self.action("tunnel-ticket", {"worldId": world, "role": "join", "allowNewPlayer": False}, user="Guest", expected=409)
        self.tunnel_flow(owner, "Host", None)
        self.action("ready", dict(owner, stage="RESUME", baseVersion=None))
        self.commit(owner, "Host", None, 1, corrupt=True)
        self.action("acquire", acquisition, expected=409)
        self.mark(self.mode + ": empty world, idempotent acquire, failed upload retry and first commit")

        owner2, view, _ = self.acquire(world, "Guest")
        require(view["hasSave"] and view["baseVersion"] == 1, "Next owner did not receive authoritative version")
        self.download(owner2, "Guest", 1)
        self.action("upload", dict(owner2, baseVersion=1, uploadId=self.uid()), user="Guest", expected=409)
        self.commit(owner2, "Guest", 1, 2)
        preview = self.action("restore-preview", {"worldId": world, "backupVersion": 1}, admin=True)
        self.action("restore", preview, admin=True, expected=400, code="RESTORE_CONFIRMATION_REQUIRED")
        restore = dict(preview, confirm=True)
        restored = self.action("restore", restore, admin=True, poll=True)
        require(restored["version"] == 3, "Restore did not publish a new version")
        require(self.action("restore", restore, admin=True, poll=True) == restored, "Restore retry differs")
        self.action("restore-preview", {"worldId": world, "backupVersion": 999}, admin=True, expected=404)
        self.mark(self.mode + ": download, second host commit, backup restore and retries")

        old, _, _ = self.acquire(world)
        self.abandon(world)
        self.action("heartbeat", old, expected=409)
        reuse = {"worldId": world, "deviceId": old["deviceId"], "sourceEpoch": old["epoch"], "baseVersion": 3}
        require(self.action("check-local-reuse", reuse)["allowed"], "Immediate local reuse should be eligible")
        reused, _, _ = self.acquire(world, extra={key: value for key, value in reuse.items() if key != "worldId"})
        self.action("cancel-creation", reused, expected=409)
        self.abandon(world)
        intervening, _, _ = self.acquire(world, "Guest")
        self.abandon(world)
        require(intervening["epoch"] > reused["epoch"], "Epoch did not advance")
        reuse["sourceEpoch"] = reused["epoch"]
        require(not self.action("check-local-reuse", reuse)["allowed"], "Intervening host allowed stale local reuse")
        self.action("acquire", dict(reuse, requestId=self.uid()), expected=409, code="LOCAL_REUSE_NOT_ALLOWED")
        final_owner, _, _ = self.acquire(world)
        self.download(final_owner, "Host", 3)
        receipt = self.commit(final_owner, "Host", 3, 4)
        backups = self.action("backups", {"worldId": world}, admin=True)
        require(backups["currentVersion"] == 4, "Backup list lost current authority")
        history = self.action("history", {"worldId": world}, admin=True)
        require({"COMMIT", "RESTORE", "ABANDON"}.issubset({item["type"] for item in history["events"]}), "Management history omitted events")
        page = self.action("history", {"worldId": world, "limit": 1}, admin=True)
        require(page["hasMore"], "History page cursor is missing")
        older = self.action("history", {"worldId": world, "limit": 1, "beforeEpoch": page["nextBeforeEpoch"]}, admin=True)
        require(older["events"][0]["epoch"] < page["events"][0]["epoch"], "History pagination did not advance")
        self.action("abandon-preview", {"worldId": world}, admin=True, expected=409)
        self.mark(self.mode + ": revocation, stale epoch, local reuse fencing and retention")

        cancelled_world = self.create(self.mode + " cancelled")
        cancelled, _, _ = self.acquire(cancelled_world)
        self.action("cancel-creation", cancelled, expected=204)
        self.action("cancel-creation", cancelled, expected=204)
        require(self.worlds()[cancelled_world]["status"] == "FREE", "Cancelled creation retained ownership")
        require(self.action("history", {"worldId": cancelled_world}, admin=True)["events"][0]["type"] == "CANCEL_CREATION", "Cancellation history is missing")
        recovery_world = self.create(self.mode + " restart recovery")
        pending, _, _ = self.acquire(recovery_world)
        self.action("ready", dict(pending, stage="SAVE_SYNCED", baseVersion=None))
        # 为下一次启动准备过期和未过期的孤立候选，验证临时目录清理的时间边界。
        for name in (".candidate-expired-pgo", ".candidate-fresh-pgo"):
            fixture = self.root / "data/worlds" / world / name
            fixture.mkdir()
            (fixture / "orphan.bin").write_bytes(b"isolated training fixture")
            if "expired" in name:
                os.utime(fixture, (0, 0))
        # 固定重复正常请求，提高业务热路径的权重，不让少量故障分支主导 profile。
        for index in range(128):
            self.call("ping", expected=204)
            self.call("worlds", user="Guest")
            self.action("heartbeat", dict(pending, playerCount=index % 4))
        return receipt, pending

    def recover(self, receipt, pending):
        # 验证重启后的回执和未提交任期仍存在，其他用户不能自动接管。
        require(self.action("commit-status", receipt)["version"] == 4, "Receipt lost after restart")
        deadline = time.monotonic() + 15
        expired = self.root / "data/worlds" / receipt["worldId"] / ".candidate-expired-pgo"
        while True:
            backups = self.action("backups", {"worldId": receipt["worldId"]}, admin=True)
            if {entry["version"] for entry in backups["backups"]} == {3, 4} and not expired.exists():
                break
            require(time.monotonic() < deadline, "Retention did not remove old backups or expired temporary fixture")
            time.sleep(0.05)
        fresh = expired.with_name(".candidate-fresh-pgo")
        require(fresh.is_dir(), "Retention removed an unexpired temporary fixture")
        require(self.worlds()[pending["worldId"]]["status"] == "RECOVERY_REQUIRED", "Restart did not retain pending ownership")
        self.action("acquire", {"worldId": pending["worldId"], "deviceId": self.uid(), "requestId": self.uid()}, user="Guest", expected=409)
        self.action("ready", dict(pending, stage="RESUME", baseVersion=None))
        certificate = self.tunnel_flow(pending, "Host", None)
        self.commit(pending, "Host", None, 1)
        self.mark(self.mode + ": restart ownership recovery and persistent receipts")
        return certificate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--collect-profiles", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("Expected executable after --")
    command[0] = str(Path(command[0]).resolve())
    root = args.work_dir.resolve()
    root.mkdir(parents=True, exist_ok=False)
    (root / "profiles").mkdir()
    (root / "data").mkdir()
    # 公钥仅作为 API 端点描述的合成夹具；训练不启动 SSH 服务，不上传任何私钥到 Actions 产物。
    key = b"ssh-ed25519"
    public = struct.pack(">I", len(key)) + key + struct.pack(">I", 32) + hashlib.sha256(b"training-host").digest()
    (root / "data/host.pub").write_text("ssh-ed25519 " + base64.b64encode(public).decode() + "\n", encoding="ascii")
    properties = {"ssh.host-public-key-file": "data/host.pub", "retention.recent-versions": 2,
                  "retention.daily-backup-days": 0, "retention.minimum-free-bytes": 0}
    workload = Workload(root)
    receipt = pending = certificate = None
    for attempt, mode in enumerate(("wss", "tcp", "tcp"), 1):
        profile = root / "profiles" / f"{attempt}-{mode}.iprof" if args.collect_profiles else None
        with smoke.running_server(command, root, mode, attempt, properties=properties, profile=profile) as ports:
            workload.api, workload.admin, workload.port = ports
            workload.mode = mode
            if attempt == 1:
                workload.register("Host")
                workload.register("Guest")
                old_auth = workload.users["Guest"]
                workload.call("register", {"username": "Guest"}, admin=True, expected=409)
                workload.register("Guest", overwrite=True)
                workload.call("worlds", headers={"Authorization": old_auth}, expected=401)
                workload.register("Disabled")
                workload.call("users/disable", {"username": "Disabled"}, admin=True)
            workload.management()
            if pending is not None:
                reloaded = workload.recover(receipt, pending)
                if certificate is not None:
                    require(certificate == reloaded, "TCP TLS certificate changed after restart")
                certificate = reloaded
            if attempt < 3:
                receipt, pending = workload.scenario()
            else:
                for _ in range(128):
                    workload.call("worlds")
    summary = {"scenarios": workload.scenarios, "requests": workload.requests,
               "forwardedBytes": workload.forwarded, "profilesCollected": args.collect_profiles}
    (root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Fixed workload passed: {len(workload.scenarios)} scenarios, {sum(workload.requests.values())} requests, "
          f"{workload.forwarded} forwarded bytes", flush=True)


if __name__ == "__main__":
    main()
