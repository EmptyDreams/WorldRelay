"""检查组合发布包和独立程序的内容、权限、说明与校验信息。"""

import hashlib
import importlib.util
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("package_release", Path(__file__).with_name("package-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PackageReleaseTest(unittest.TestCase):
    def fixtures(self, root):
        source = root / "inputs"
        source.mkdir()
        admins = []
        for arch in ("arm64", "x64"):
            server = source / f"worldrelay-server-linux-{arch}.tar.gz"
            release.write_archive(server, {"worldrelay-server": (f"server-{arch}".encode(), 0o644),
                                           "README.md": (b"server usage", 0o644),
                                           "licenses/zlib/README": (b"zlib notice", 0o644)})
            Path(str(server) + ".sha256").write_text(f"{hashlib.sha256(server.read_bytes()).hexdigest()}  {server.name}\n")
            admin = source / f"worldrelay-admin-linux-{arch}.tar.gz"
            release.write_archive(admin, {"worldrelay-admin": (f"admin-{arch}".encode(), 0o644),
                                          "README.md": (b"admin usage [setup](../server/README.md)", 0o644)})
            admins.append(f"{hashlib.sha256(admin.read_bytes()).hexdigest()}  {admin.name}\n")
        (source / "SHA256SUMS").write_text("".join(admins))
        (source / "worldrelay-server.jar").write_bytes(b"portable server jar")
        (source / "worldrelay-server.jar.sha256").write_text(
            hashlib.sha256(b"portable server jar").hexdigest() + "  worldrelay-server.jar\n")
        return source

    # 四个包都携带正确架构的管理端；原生版保留许可证，JAR 版提供可执行启动脚本。
    def test_packages_pair_the_server_and_admin_for_each_architecture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outputs = release.package(self.fixtures(root), root / "release")
            self.assertEqual({path.name for path in outputs}, {
                "worldrelay-linux-arm64.tar.gz", "worldrelay-linux-x64.tar.gz",
                "worldrelay-jar-linux-arm64.tar.gz", "worldrelay-jar-linux-x64.tar.gz",
                "worldrelay-server.jar", "worldrelay-admin-linux-arm64", "worldrelay-admin-linux-x64",
                "worldrelay-server-linux-arm64", "worldrelay-server-linux-x64"})
            for path in (path for path in outputs if path.name.endswith(".tar.gz")):
                arch = "arm64" if "arm64" in path.name else "x64"
                with self.subTest(package=path.name), tarfile.open(path) as archive:
                    self.assertEqual(archive.extractfile("worldrelay-admin").read(), f"admin-{arch}".encode())
                    self.assertEqual(archive.extractfile("README.md").read(), b"server usage")
                    self.assertEqual(archive.extractfile("ADMIN.md").read(), b"admin usage [setup](README.md)")
                    for program in ("worldrelay-server", "worldrelay-admin"):
                        self.assertEqual(archive.getmember(program).mode, 0o755)
                    if "-jar-" in path.name:
                        self.assertEqual(archive.extractfile("worldrelay-server.jar").read(), b"portable server jar")
                        launcher = archive.extractfile("worldrelay-server").read()
                        self.assertTrue(launcher.startswith(b"#!/bin/sh\n"))
                    else:
                        self.assertEqual(archive.extractfile("worldrelay-server").read(), f"server-{arch}".encode())
                        self.assertEqual(archive.extractfile("licenses/zlib/README").read(), b"zlib notice")
            for line in (root / "release/SHA256SUMS").read_text().splitlines():
                digest, name = line.split()
                self.assertEqual(digest, hashlib.sha256((root / "release" / name).read_bytes()).hexdigest())

    # 独立下载的 JAR、服务端和管理端与组合包内容一致，均有可验证的 SHA-256 摘要。
    def test_standalone_programs_match_bundles_and_have_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            destination = root / "release"
            release.package(self.fixtures(root), destination)
            jar = destination / "worldrelay-server.jar"
            self.assertEqual(jar.read_bytes(), b"portable server jar")
            release.verify_checksum(jar, destination / "SHA256SUMS")
            for arch in ("arm64", "x64"):
                server = destination / f"worldrelay-server-linux-{arch}"
                self.assertEqual(server.read_bytes(), f"server-{arch}".encode())
                release.verify_checksum(server, destination / "SHA256SUMS")
                admin = destination / f"worldrelay-admin-linux-{arch}"
                self.assertEqual(admin.read_bytes(), f"admin-{arch}".encode())
                release.verify_checksum(admin, destination / "SHA256SUMS")
                for edition in ("", "-jar"):
                    with tarfile.open(destination / f"worldrelay{edition}-linux-{arch}.tar.gz") as archive:
                        self.assertEqual(archive.extractfile("worldrelay-admin").read(), admin.read_bytes())
                        if edition:
                            self.assertEqual(archive.extractfile("worldrelay-server.jar").read(), jar.read_bytes())
                        else:
                            self.assertEqual(archive.extractfile("worldrelay-server").read(), server.read_bytes())

    # 校验不通过的输入不能继续组合，避免将损坏或混用的构建产物发布出去。
    def test_corrupt_input_prevents_packaging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.fixtures(root)
            (source / "worldrelay-admin-linux-arm64.tar.gz").write_bytes(b"corrupted download")
            with self.assertRaisesRegex(ValueError, "Checksum mismatch: worldrelay-admin-linux-arm64"):
                release.package(source, root / "release")
            self.assertFalse((root / "release").exists())


if __name__ == "__main__":
    unittest.main()
