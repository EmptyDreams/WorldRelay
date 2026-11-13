#!/usr/bin/env bash
set -euo pipefail

architecture=${1:?Usage: native-image.sh x64|arm64 [native-image options]}
shift
case "$architecture" in
  x64)
    exec native-image --static --libc=musl "$@"
    ;;
  arm64)
    workspace=$(pwd -P)
    # 编译在 OL8 中进行；训练和验收仍在 runner 上运行，复用原有 SSH/rsync 环境。
    exec docker run --rm --platform linux/arm64 \
      --user "$(id -u):$(id -g)" --env HOME=/tmp \
      --mount "type=bind,src=$workspace,dst=$workspace" --workdir "$workspace" \
      "${ARM64_NATIVE_IMAGE:-worldrelay-native-arm64:build}" \
      -J-Duser.home=/tmp --static-nolibc "$@"
    ;;
  *) echo "Unsupported Native Image architecture: $architecture" >&2; exit 1 ;;
esac
