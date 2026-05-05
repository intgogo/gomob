#!/bin/bash
# server-doctor.sh — gomob 服务端工具链自检
#
# 校验：Go 1.23+ / docker / docker-compose 或 docker compose / protoc / git
# 缺失项给出 CentOS 9 / Debian 安装命令。

set -uo pipefail

REQUIRED_GO_MAJOR=1
REQUIRED_GO_MINOR=23

ok=0
warn=0
fail=0

green()  { printf "\033[32m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }

check_go() {
    if ! command -v go >/dev/null 2>&1; then
        red "✗ Go 未安装"
        echo "    建议：dnf install -y golang   或下载 https://go.dev/dl/"
        ((fail++)); return
    fi
    local v major minor
    v=$(go version | awk '{print $3}' | sed 's/^go//')
    major=$(echo "$v" | cut -d. -f1)
    minor=$(echo "$v" | cut -d. -f2)
    if [[ $major -lt $REQUIRED_GO_MAJOR ]] || \
       { [[ $major -eq $REQUIRED_GO_MAJOR ]] && [[ $minor -lt $REQUIRED_GO_MINOR ]]; }; then
        red "✗ 需要 Go ${REQUIRED_GO_MAJOR}.${REQUIRED_GO_MINOR}+，当前 $v"
        ((fail++))
    else
        green "✓ Go $v"
        ((ok++))
    fi
}

check_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        red "✗ docker 未安装"
        echo "    建议：dnf install -y docker      （CentOS 9 自带 podman 但 podman compose 不兼容）"
        ((fail++)); return
    fi
    green "✓ $(docker --version)"
    ((ok++))
}

check_compose() {
    # 优先 docker compose 子命令（v2 plugin），降级到 docker-compose（v1 二进制）
    if docker compose version >/dev/null 2>&1; then
        green "✓ $(docker compose version | head -1)"
        ((ok++))
        return
    fi
    if command -v docker-compose >/dev/null 2>&1; then
        yellow "· docker-compose v1（建议升级到 v2 plugin）：$(docker-compose --version)"
        ((warn++))
        return
    fi
    red "✗ docker compose 缺失"
    echo "    建议（v2 plugin）："
    echo "      mkdir -p ~/.docker/cli-plugins/"
    echo "      curl -sSLo ~/.docker/cli-plugins/docker-compose \\"
    echo "        https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64"
    echo "      chmod +x ~/.docker/cli-plugins/docker-compose"
    ((fail++))
}

check_protoc() {
    if ! command -v protoc >/dev/null 2>&1; then
        yellow "· protoc 未装（make proto 会失败；非 M-S0 必需，proto 阶段再补）"
        echo "    建议：dnf install -y protobuf-compiler  &&  go install google.golang.org/protobuf/cmd/protoc-gen-go@latest  &&  go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest"
        ((warn++))
        return
    fi
    green "✓ $(protoc --version)"
    ((ok++))
}

check_git() {
    if ! command -v git >/dev/null 2>&1; then
        red "✗ git 未装"
        ((fail++)); return
    fi
    green "✓ $(git --version)"
    ((ok++))
}

echo "── gomob server doctor ──"
check_go
check_docker
check_compose
check_protoc
check_git

echo
echo "汇总：${ok} 通过 / ${warn} 警告 / ${fail} 失败"

if [[ $fail -gt 0 ]]; then
    exit 1
fi
exit 0
