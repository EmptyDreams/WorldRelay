#!/usr/bin/env bash
set -euo pipefail

missing=()
for package in build-essential zlib1g-dev; do
  if [[ "$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null)" != "install ok installed" ]]; then
    missing+=("$package")
  fi
done
if (( ${#missing[@]} == 0 )); then
  echo "Native toolchain is already installed; skipping APT."
  exit 0
fi

source /etc/os-release
: "${VERSION_CODENAME:?Ubuntu release codename is missing}"
architecture=$(dpkg --print-architecture)
case "$architecture" in
  amd64)
    repository=ubuntu
    official=https://archive.ubuntu.com/ubuntu
    official_security=https://security.ubuntu.com/ubuntu
    ;;
  arm64)
    repository=ubuntu-ports
    official=https://ports.ubuntu.com/ubuntu-ports
    official_security=$official
    ;;
  *) echo "::error::Unsupported native build architecture: $architecture"; exit 1 ;;
esac

# 只为本次依赖安装选择源，避免更新运行器中无关的第三方仓库。
apt_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/worldrelay-apt.XXXXXX")
trap 'rm -rf -- "$apt_dir"' EXIT
mkdir "$apt_dir/sources.list.d"
apt_options=(
  -o "Dir::Etc::sourcelist=$apt_dir/sources.list"
  -o "Dir::Etc::sourceparts=$apt_dir/sources.list.d"
  -o APT::Get::List-Cleanup=0
  -o Acquire::Retries=0
  -o Acquire::http::Timeout=10
  -o Acquire::https::Timeout=10
)

run_apt() {
  local limit=$1
  shift
  # APT 的连接超时无法限制整个 DNS/下载过程，用总超时及时切换镜像。
  sudo timeout --kill-after=5s "$limit" apt-get "${apt_options[@]}" "$@"
}

for archive in "$official" \
  "https://mirrors.tuna.tsinghua.edu.cn/$repository" \
  "https://mirrors.ustc.edu.cn/$repository"; do
  security=$archive
  if [[ "$archive" == "$official" ]]; then security=$official_security; fi
  hosts=("${archive#https://}")
  if [[ "$security" != "$archive" ]]; then hosts+=("${security#https://}"); fi
  reachable=true
  for address in "${hosts[@]}"; do
    host=${address%%/*}
    if ! timeout --kill-after=1s 3s getent ahosts "$host" >/dev/null; then
      echo "::warning::DNS lookup failed or timed out for $host; trying the next mirror."
      reachable=false
      break
    fi
  done
  if [[ "$reachable" == false ]]; then continue; fi

  echo "Using Ubuntu mirror: $archive ($architecture, $VERSION_CODENAME)"
  options="arch=$architecture signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg"
  printf 'deb [%s] %s %s main\n' \
    "$options" "$archive" "$VERSION_CODENAME" \
    "$options" "$archive" "$VERSION_CODENAME-updates" \
    "$options" "$security" "$VERSION_CODENAME-security" > "$apt_dir/sources.list"

  # 下载阶段失败可安全换源；下载完成后再本地安装，避免重试中途打断 dpkg。
  if run_apt 30s --error-on=any update && \
    run_apt 60s --download-only install --no-install-recommends -y "${missing[@]}"; then
    if ! run_apt 120s --no-download install --no-install-recommends -y "${missing[@]}"; then
      echo "::error::Local toolchain installation failed after packages were downloaded."
      exit 1
    fi
    exit 0
  fi
  echo "::warning::APT update or download failed for $archive; trying the next mirror."
done

echo "::group::APT DNS diagnostics"
cat /etc/resolv.conf || true
echo "::endgroup::"
echo "::error::Official Ubuntu repositories and both fallback mirrors are unavailable."
exit 1
