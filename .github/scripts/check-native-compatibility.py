"""检查 x64 完全静态链接，以及 ARM64 仅依赖旧版 glibc。"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def validate(arch, machine, segments, dynamic, versions):
    expected_machine = {"x64": 62, "arm64": 183}[arch]
    if machine != expected_machine:
        raise ValueError(f"Expected {arch} ELF, got machine {machine}")
    interpreter = bool(re.search(r"^\s*INTERP\s", segments, re.MULTILINE))
    libraries = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic)
    names = set(re.findall(r"\bGLIBC_[A-Za-z0-9_.]+", versions))
    required = {tuple(map(int, name.removeprefix("GLIBC_").split(".")))
                for name in names if re.fullmatch(r"GLIBC_[0-9]+(?:\.[0-9]+)+", name)}
    unknown = names - {"GLIBC_" + ".".join(map(str, version)) for version in required}
    maximum = max(required) if required else None
    if arch == "x64":
        if interpreter or libraries or names:
            raise ValueError(f"x64 must be fully static: interpreter={interpreter}, libraries={libraries}, glibc={sorted(names)}")
    elif not interpreter or maximum is None or maximum > (2, 28) or unknown:
        raise ValueError(f"ARM64 must use glibc with required symbol versions at most 2.28; found {sorted(names)}")
    else:
        glibc_libraries = {"libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1",
                           "libresolv.so.2", "libutil.so.1", "libanl.so.1", "ld-linux-aarch64.so.1"}
        external = set(libraries) - glibc_libraries
        if external:
            raise ValueError(f"ARM64 must dynamically link only glibc; found {sorted(external)}")
    return {"arch": arch, "interpreter": interpreter, "libraries": libraries,
            "maximumGlibc": ".".join(map(str, maximum)) if maximum else None}


def inspect(executable, arch):
    with executable.open("rb") as source:
        header = source.read(20)
    if len(header) < 20 or header[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 executable")
    environment = dict(os.environ, LC_ALL="C")

    def readelf(option):
        return subprocess.check_output(["readelf", "-W", option, str(executable)],
                                       text=True, stderr=subprocess.STDOUT, env=environment)

    return validate(arch, int.from_bytes(header[18:20], "little"),
                    readelf("-l"), readelf("-d"), readelf("-V"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arch", required=True, choices=("x64", "arm64"))
    parser.add_argument("--report", type=Path)
    parser.add_argument("executable", type=Path)
    args = parser.parse_args()
    try:
        result = inspect(args.executable, args.arch)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Native compatibility check failed: {error}\n")
    output = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
