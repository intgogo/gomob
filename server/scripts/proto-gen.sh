#!/bin/bash
# proto-gen.sh — 把 proto/*.proto 编译成 .pb.go + .pb.grpc.go
#
# 依赖（缺则报具体安装命令）：
#   - protoc（dnf install -y protobuf-compiler）
#   - protoc-gen-go（go install google.golang.org/protobuf/cmd/protoc-gen-go@latest）
#   - protoc-gen-go-grpc（go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest）
#
# 输出位置：与 .proto 同目录的 .pb.go / _grpc.pb.go。
# Go 包路径：每个 .proto 顶部用 `option go_package = "io.gomob/server/proto/...";` 声明。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROTO_DIR="$ROOT/proto"

require() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "✗ 缺 $1" >&2
        echo "  $2" >&2
        exit 1
    fi
}

# protoc-gen-go / protoc-gen-go-grpc 装到 GOPATH/bin，protoc 通过 PATH 找它们
GOBIN="${GOPATH:-$HOME/go}/bin"
export PATH="$GOBIN:$PATH"

require protoc                "dnf install -y protobuf-compiler"
require protoc-gen-go         "go install google.golang.org/protobuf/cmd/protoc-gen-go@latest"
require protoc-gen-go-grpc    "go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest"

shopt -s nullglob
files=("$PROTO_DIR"/*.proto)
shopt -u nullglob

if [[ ${#files[@]} -eq 0 ]]; then
    echo "· proto/ 当前没有 .proto 文件 — 各 M-S 阶段交付时再补"
    exit 0
fi

for f in "${files[@]}"; do
    echo "→ $(basename "$f")"
    protoc \
        --proto_path="$PROTO_DIR" \
        --go_out=paths=source_relative:"$PROTO_DIR" \
        --go-grpc_out=paths=source_relative:"$PROTO_DIR" \
        "$f"
done

echo "ready."
