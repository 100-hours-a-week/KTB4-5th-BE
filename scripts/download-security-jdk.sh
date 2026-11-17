#!/usr/bin/env bash
set -euo pipefail

# CVE-2026-114938 수정 빌드. 버전과 SHA-256은 공식 릴리스에서 함께 갱신한다.
# https://github.com/corretto/corretto-25/releases/tag/25.0.5.1.2
version=25.0.5.1.2
archive=${1:?다운로드할 아카이브 경로가 필요합니다.}
case "${2:-$(uname -m)}" in
  amd64|x86_64)
    architecture=x64
    checksum=8d2be3f035dbdb7f7475e2f7009b7dc20683bfc351ad41064218c8de596c9644
    ;;
  arm64|aarch64)
    architecture=aarch64
    checksum=9370d3e5bcf0d165e6be4a78c3f6b57160af16d3033319af583800251eb91df2
    ;;
  *) echo "지원하지 않는 아키텍처입니다: ${2:-$(uname -m)}" >&2; exit 1 ;;
esac

curl --fail --location --retry 3 --output "$archive" \
  "https://corretto.aws/downloads/resources/$version/amazon-corretto-$version-linux-$architecture.tar.gz"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check
