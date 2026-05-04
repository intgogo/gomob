#!/bin/bash
# ensure-go.sh — Go 工具链自检
set -euo pipefail

REQUIRED_MAJOR=1
REQUIRED_MINOR=23

if ! command -v go >/dev/null 2>&1; then
    echo "✗ Go 未安装。建议: dnf install -y golang  或下载 https://go.dev/dl/" >&2
    exit 1
fi

GO_VERSION=$(go version | awk '{print $3}' | sed 's/^go//')
GO_MAJOR=$(echo "$GO_VERSION" | cut -d. -f1)
GO_MINOR=$(echo "$GO_VERSION" | cut -d. -f2)

if [[ $GO_MAJOR -lt $REQUIRED_MAJOR ]] || \
   { [[ $GO_MAJOR -eq $REQUIRED_MAJOR ]] && [[ $GO_MINOR -lt $REQUIRED_MINOR ]]; }; then
    echo "✗ 需要 Go $REQUIRED_MAJOR.$REQUIRED_MINOR+，当前 $GO_VERSION" >&2
    exit 1
fi

echo "✓ Go $GO_VERSION"

if ! command -v docker >/dev/null 2>&1; then
    echo "· docker 未装（make up 会失败）。dev 不强制要装"
fi

if ! command -v protoc >/dev/null 2>&1; then
    echo "· protoc 未装（make proto 会失败）。需要时:  dnf install -y protobuf-compiler"
fi

echo "ready."
