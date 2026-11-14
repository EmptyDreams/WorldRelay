"""检查源码下载的镜像回退、摘要校验和缓存发布。"""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


BASH = os.environ.get("BASH") or shutil.which("bash")
SCRIPT = Path(__file__).with_name("download-source.sh").resolve()


@unittest.skipUnless(BASH, "Source download checks require Bash")
class SourceDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="worldrelay-download-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.payload = b"verified-source-archive"
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.target = self.directory / "source.tar.gz"
        self.calls = self.directory / "calls"
        (self.directory / "payload").write_bytes(self.payload)
        self.tools = tools = self.directory / "bin"
        tools.mkdir()
        curl = tools / "curl"
        curl.write_text("""#!/usr/bin/env bash
set -euo pipefail
url='' output=''
while (( $# > 0 )); do
  case "$1" in
    --output) output=$2; shift 2 ;;
    https://*) url=$1; shift ;;
    *) shift ;;
  esac
done
printf '%s\\n' "$url" >> "$CURL_CALLS"
case "$url" in
  */timeout) printf 'partial' > "$output"; exit 28 ;;
  */corrupt) printf 'corrupt' > "$output" ;;
  */valid) cp "$CURL_PAYLOAD" "$output" ;;
  *) exit 22 ;;
esac
""", encoding="utf-8", newline="\n")
        curl.chmod(0o755)
        self.environment = os.environ | {
            "CURL_CALLS": self.calls.as_posix(),
            "CURL_PAYLOAD": (self.directory / "payload").as_posix(),
        }

    def download(self, *sources):
        # 在 Bash 内设置模拟工具路径，兼容 Git Bash 对 Windows PATH 的初始化处理。
        launcher = 'export PATH="$(cd -- "$1" && pwd):$PATH"; shift; source "$@"'
        return subprocess.run([BASH, "-c", launcher, "source-download-test", self.tools.as_posix(),
                               SCRIPT.as_posix(), self.target.as_posix(), self.digest, *sources],
                              env=self.environment, capture_output=True, text=True, timeout=20)

    # 首选源超时、备用源内容错误时继续回退，只发布通过固定摘要校验的文件。
    def test_falls_back_after_timeout_and_checksum_mismatch(self):
        sources = ["https://primary/timeout", "https://mirror/corrupt", "https://mirror/valid"]
        result = self.download(*sources)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertEqual(self.calls.read_text().splitlines(), sources)
        self.assertEqual(list(self.directory.glob("*.part.*")), [])

    # 已缓存且摘要正确的源码直接复用，不依赖任何下载站点。
    def test_reuses_verified_cache_without_downloading(self):
        self.target.write_bytes(self.payload)
        result = self.download("https://primary/timeout")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertFalse(self.calls.exists())

    # 全部源失败时返回错误并清理临时下载，不用失败内容覆盖已有缓存。
    def test_failed_sources_preserve_existing_cache(self):
        previous = b"previous-source-archive"
        self.target.write_bytes(previous)
        sources = ["https://primary/timeout", "https://mirror/corrupt"]
        result = self.download(*sources)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.target.read_bytes(), previous)
        self.assertEqual(self.calls.read_text().splitlines(), sources)
        self.assertEqual(list(self.directory.glob("*.part.*")), [])
        self.assertIn("All source URLs failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
