#!/usr/bin/env bash
set -euo pipefail

target=${1:?Usage: download-source.sh TARGET SHA256 URL...}
digest=${2:?Usage: download-source.sh TARGET SHA256 URL...}
shift 2
(( $# > 0 )) || { echo 'At least one source URL is required.' >&2; exit 1; }

if [[ -f "$target" ]] && printf '%s  %s\n' "$digest" "$target" | sha256sum --check --status; then
  echo "Using verified source cache: $target"
  exit 0
fi

temporary=$(mktemp "$target.part.XXXXXX")
trap 'rm -f -- "$temporary"' EXIT
for url in "$@"; do
  echo "Downloading $(basename -- "$target") from $url"
  # 小型源码包限时尝试，避免反复等待同一个不可达的站点。
  if curl --fail --location --silent --show-error --retry 0 --connect-timeout 10 --max-time 30 \
    "$url" --output "$temporary"; then
    if printf '%s  %s\n' "$digest" "$temporary" | sha256sum --check --status; then
      mv -- "$temporary" "$target"
      echo "Source checksum verified: $target"
      exit 0
    fi
    echo "::warning::Source checksum mismatch for $url; trying the next source."
  else
    echo "::warning::Source download failed for $url; trying the next source."
  fi
done

echo "::error::All source URLs failed for $target."
exit 1
