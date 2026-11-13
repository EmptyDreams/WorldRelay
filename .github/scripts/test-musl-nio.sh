#!/usr/bin/env bash
set -euo pipefail

directory=$(realpath -m "${1:?Usage: test-musl-nio.sh DIRECTORY}")
source_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../native-image" && pwd -P)
mkdir -p "$directory"
compiler=${MUSL_CC:-x86_64-linux-musl-gcc}
"$compiler" -O2 -fPIC -Wall -Wextra -Werror -c "$source_dir/musl-nio.c" -o "$directory/musl-nio.o"
# Native Image 会把自定义链接选项放在库列表之后，探针覆盖相同的目标文件顺序。
"$compiler" -static -O2 -Wall -Wextra -Werror "$source_dir/test-musl-nio.c" \
  -Wl,--gc-sections -lpthread -ldl -lc "$directory/musl-nio.o" -Wl,--wrap=dlsym \
  -o "$directory/musl-nio-test"
"$directory/musl-nio-test" "$directory"
