"""组合分架构发布包，同时提供可单独下载的服务端、管理终端和 JAR。"""

import argparse
import hashlib
import io
from pathlib import Path, PurePosixPath
import tarfile


def verify_checksum(path, manifest):
    for line in manifest.read_text(encoding="utf-8").splitlines():
        fields = line.split(maxsplit=1)
        if len(fields) == 2 and fields[1].lstrip("*") == path.name:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != fields[0].lower():
                raise ValueError(f"Checksum mismatch: {path.name}")
            return
    raise ValueError(f"Missing checksum: {path.name}")


def read_file(archive, name):
    member = archive.getmember(name)
    if not member.isfile():
        raise ValueError(f"Expected a regular file: {name}")
    return archive.extractfile(member).read()


def server_files(path):
    with tarfile.open(path, "r:gz") as archive:
        files = {"worldrelay-server": (read_file(archive, "worldrelay-server"), 0o755),
                 "README.md": (read_file(archive, "README.md"), 0o644)}
        for member in archive.getmembers():
            if not member.name.startswith("licenses/") or member.isdir():
                continue
            name = PurePosixPath(member.name)
            if ".." in name.parts:
                raise ValueError(f"Invalid license path: {member.name}")
            files[member.name] = (read_file(archive, member.name), 0o644)
    return files


def write_archive(path, files):
    with tarfile.open(path, "w:gz", compresslevel=6) as archive:
        for name, (data, mode) in sorted(files.items()):
            member = tarfile.TarInfo(name)
            member.size = len(data)
            member.mode = mode
            archive.addfile(member, io.BytesIO(data))


def package(input_dir, output_dir):
    if input_dir.resolve() == output_dir.resolve():
        raise ValueError("Input and output directories must be different")
    architectures = ("arm64", "x64")
    for arch in architectures:
        name = f"worldrelay-server-linux-{arch}.tar.gz"
        verify_checksum(input_dir / name, input_dir / (name + ".sha256"))
        verify_checksum(input_dir / f"worldrelay-admin-linux-{arch}.tar.gz", input_dir / "SHA256SUMS")
    verify_checksum(input_dir / "worldrelay-server.jar", input_dir / "worldrelay-server.jar.sha256")

    launcher = Path(__file__).resolve().parents[1] / "release/worldrelay-server"
    jar = (input_dir / "worldrelay-server.jar").read_bytes()
    output_dir.mkdir(parents=True, exist_ok=True)
    assets = []
    for arch in architectures:
        native = server_files(input_dir / f"worldrelay-server-linux-{arch}.tar.gz")
        with tarfile.open(input_dir / f"worldrelay-admin-linux-{arch}.tar.gz", "r:gz") as archive:
            admin = (read_file(archive, "worldrelay-admin"), 0o755)
            admin_readme = (read_file(archive, "README.md").replace(b"(../server/README.md)", b"(README.md)"), 0o644)
        native.update({"worldrelay-admin": admin, "ADMIN.md": admin_readme})
        jar_files = {"worldrelay-server.jar": (jar, 0o644),
                     "worldrelay-server": (launcher.read_bytes(), 0o755),
                     "worldrelay-admin": admin, "README.md": native["README.md"], "ADMIN.md": admin_readme}
        for name, files in ((f"worldrelay-linux-{arch}.tar.gz", native),
                            (f"worldrelay-jar-linux-{arch}.tar.gz", jar_files)):
            destination = output_dir / name
            write_archive(destination, files)
            assets.append(destination)
        for program, (data, mode) in (("server", native["worldrelay-server"]), ("admin", admin)):
            standalone = output_dir / f"worldrelay-{program}-linux-{arch}"
            standalone.write_bytes(data)
            standalone.chmod(mode)
            assets.append(standalone)

    standalone_jar = output_dir / "worldrelay-server.jar"
    standalone_jar.write_bytes(jar)
    assets.append(standalone_jar)
    lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in assets]
    (output_dir / "SHA256SUMS").write_text("".join(lines), encoding="ascii")
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        for path in package(args.input_dir, args.output_dir):
            print(path)
    except (OSError, KeyError, ValueError, tarfile.TarError) as error:
        parser.exit(1, f"Release packaging failed: {error}\n")


if __name__ == "__main__":
    main()
