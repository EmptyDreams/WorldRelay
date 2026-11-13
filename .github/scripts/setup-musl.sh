#!/usr/bin/env bash
set -euo pipefail

[[ $(uname -m) == x86_64 ]] || { echo 'The musl toolchain is built for Linux x64 only.' >&2; exit 1; }
directory=$(realpath -m "${1:?Usage: setup-musl.sh DIRECTORY}")
prefix=$directory/toolchain
stamp='musl-1.2.6-iconv-67219f0-zlib-1.4-v2'
for tool in gcc make patch curl tar sha256sum; do
  command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done

if [[ ! -f "$prefix/.worldrelay-version" ]] || [[ $(cat "$prefix/.worldrelay-version") != "$stamp" ]] || \
   [[ ! -x "$prefix/bin/x86_64-linux-musl-gcc" ]] || [[ ! -f "$prefix/lib/libz.a" ]]; then
  mkdir -p "$directory/downloads" "$prefix"
  download() {
    local url=$1 name=$2 digest=$3
    if [[ ! -f "$directory/downloads/$name" ]] || \
       ! (cd "$directory/downloads" && printf '%s  %s\n' "$digest" "$name" | sha256sum --check --status); then
      curl --fail --location --retry 1 --retry-delay 1 --connect-timeout 10 --max-time 60 \
        "$url" --output "$directory/downloads/$name"
      (cd "$directory/downloads" && printf '%s  %s\n' "$digest" "$name" | sha256sum --check)
    fi
  }
  download https://musl.libc.org/releases/musl-1.2.6.tar.gz musl.tar.gz \
    d585fd3b613c66151fc3249e8ed44f77020cb5e6c1e635a616d3f9f82460512a
  # 上游 1.2.6 后修复的 GB18030 解码问题；补丁随源码摘要固定，不使用浮动分支。
  download https://www.openwall.com/lists/musl/2026/04/03/2/1 musl-iconv.patch \
    444fa70e52ca158fb7d4bad560637790bbf8f72e80b82fff840dd66fa83091e3
  download https://zlib.net/fossils/zlib-1.4.tar.gz zlib.tar.gz \
    02dd159783677a749c04adbdd02b55b8a2bcc7e054f51b76b549199a916ad584

  build_dir=$(mktemp -d "$directory/build.XXXXXX")
  trap 'rm -rf -- "$build_dir"' EXIT
  tar -xzf "$directory/downloads/musl.tar.gz" -C "$build_dir"
  tar -xzf "$directory/downloads/zlib.tar.gz" -C "$build_dir"
  jobs=$(nproc)
  (
    cd "$build_dir/musl-1.2.6"
    patch --batch -p1 < "$directory/downloads/musl-iconv.patch"
    CFLAGS='-O2 -pipe -march=x86-64 -mtune=generic' ./configure \
      --prefix="$prefix" --syslibdir="$prefix/lib" --enable-static --disable-shared
    make -j"$jobs"
    make install
  )
  ln -sf musl-gcc "$prefix/bin/x86_64-linux-musl-gcc"
  (
    cd "$build_dir/zlib-1.4"
    CC="$prefix/bin/musl-gcc" CFLAGS='-O2 -pipe -march=x86-64 -mtune=generic' LDFLAGS='-static' \
      ./configure --prefix="$prefix" --static
    make -j"$jobs"
    make check
    make install
  )
  mkdir -p "$prefix/share/licenses"
  cp "$build_dir/musl-1.2.6/COPYRIGHT" "$prefix/share/licenses/musl.txt"
  cp "$build_dir/zlib-1.4/LICENSE" "$prefix/share/licenses/zlib.txt"
  printf '%s\n' "$stamp" > "$prefix/.worldrelay-version"
fi

"$prefix/bin/x86_64-linux-musl-gcc" --version
if [[ -n ${GITHUB_PATH:-} ]]; then printf '%s\n' "$prefix/bin" >> "$GITHUB_PATH"; fi
if [[ -n ${GITHUB_ENV:-} ]]; then printf 'MUSL_TOOLCHAIN=%s\n' "$prefix" >> "$GITHUB_ENV"; fi
echo "musl toolchain ready: $prefix"
