"""检查 CI 启动脚本的进程关闭判定，无需编译原生程序。"""

import importlib.util
import signal
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("smoke_server", Path(__file__).with_name("smoke-server.py"))
smoke_server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke_server)


class ShutdownTest(unittest.TestCase):
    # 主动关闭后，兼容正常返回、直接收到 SIGTERM，以及信号处理器返回 143。
    def test_requested_shutdown_accepts_expected_exit_statuses(self):
        for code in (0, -signal.SIGTERM, 128 + signal.SIGTERM):
            with self.subTest(code=code), patch.object(smoke_server.os, "name", "posix"):
                process = Mock(returncode=code)
                process.poll.return_value = None
                smoke_server.stop_server(process, "server.log")
                process.terminate.assert_called_once_with()
                process.wait.assert_called_once_with(timeout=15)
                process.kill.assert_not_called()

    # 关闭期间出现应用错误、SIGKILL 或崩溃，仍判定为失败。
    def test_requested_shutdown_rejects_unexpected_exit_statuses(self):
        for code in (1, -9, 137, 139):
            with self.subTest(code=code), patch.object(smoke_server.os, "name", "posix"):
                process = Mock(returncode=code)
                process.poll.return_value = None
                with self.assertRaisesRegex(RuntimeError, f"Server exited with {code}"):
                    smoke_server.stop_server(process, "server.log")

    # 服务在脚本发出关闭请求前就已退出时，不能把 0 或 143 当作检查成功。
    def test_exit_before_shutdown_is_a_failure(self):
        for code in (0, 143):
            with self.subTest(code=code):
                process = Mock(returncode=code)
                process.poll.return_value = code
                with self.assertRaisesRegex(RuntimeError, "before requested shutdown"):
                    smoke_server.stop_server(process, "server.log")
                process.terminate.assert_not_called()
                process.wait.assert_not_called()

    # 关闭超时后清理进程，同时保留超时失败，不能因后续退出而通过。
    def test_shutdown_timeout_kills_process_and_fails(self):
        process = Mock(returncode=-9)
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("server", 15), -9]
        with self.assertRaisesRegex(RuntimeError, "Server did not shut down"):
            smoke_server.stop_server(process, "server.log")
        process.terminate.assert_called_once_with()
        process.kill.assert_called_once_with()
        self.assertEqual(process.wait.call_count, 2)


class ProfileTest(unittest.TestCase):
    def launch(self, command, *, cwd, stdout, **_):
        stdout.write(b"WorldRelay HTTP listening on /127.0.0.1:10001\n"
                     b"WorldRelay registration listening on /127.0.0.1:10002\n"
                     b"WorldRelay tunnel (WSS) listening on /127.0.0.1:10003\n")
        stdout.flush()
        process = Mock(returncode=0)
        process.poll.return_value = None
        return process

    # 指定的 profile 路径传给插桩进程，且在进程关闭后检查导出结果。
    def test_profile_is_exported_before_server_context_completes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "training.iprof"

            def export_at_shutdown(*args, **options):
                process = self.launch(*args, **options)
                process.wait.side_effect = lambda timeout: profile.write_bytes(b"profile-generated-at-exit")
                return process

            with patch.object(smoke_server.subprocess, "Popen", side_effect=export_at_shutdown) as launch:
                with smoke_server.running_server(["server"], root, "wss", 1, profile=profile) as ports:
                    self.assertEqual(ports, (10001, 10002, 10003))
                    self.assertFalse(profile.exists())
                self.assertIn(f"-XX:ProfilesDumpFile={profile}", launch.call_args.args[0])
                self.assertEqual(profile.read_bytes(), b"profile-generated-at-exit")

    # 即使业务流程通过，缺失或空 profile 仍需使训练失败，不能静默退回普通构建。
    def test_missing_or_empty_profile_fails_training(self):
        for empty in (False, True):
            with self.subTest(empty=empty), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                profile = root / "training.iprof"
                with patch.object(smoke_server.subprocess, "Popen", side_effect=self.launch):
                    with self.assertRaisesRegex(RuntimeError, "PGO profile was not written"):
                        with smoke_server.running_server(["server"], root, "wss", 1, profile=profile):
                            if empty:
                                profile.touch()


if __name__ == "__main__":
    unittest.main()
