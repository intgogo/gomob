#!/bin/bash
# server-doctor.sh — gomob 服务端工具链自检
#
# 校验：Go 1.23+ / podman（容器运行时）/ protoc / git
# 缺失项给出 CentOS 9 / Debian 安装命令。
#
# 容器策略：podman 全程负责（dev + prod）。podman 出 OCI 标准镜像，
# 部署到任意容器引擎（docker / containerd / k8s）都跑得动，不需要双栈。

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

check_podman() {
    if ! command -v podman >/dev/null 2>&1; then
        red "✗ podman 未安装（开发栈强依赖）"
        echo "    建议：dnf install -y podman"
        ((fail++)); return
    fi
    green "✓ $(podman --version)"
    ((ok++))

    # 校验 4 个 dev 容器是否已 ready（可缺，会引导用户起）
    local containers="gomob-pg gomob-redis gomob-nats gomob-minio"
    local missing=()
    for c in $containers; do
        podman ps -a --format '{{.Names}}' | grep -qx "$c" || missing+=("$c")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        yellow "· dev 栈缺容器：${missing[*]}"
        echo "    建议：手动 podman run 起一次（持久 named volume）："
        echo "      podman run -d --name gomob-pg    -p 5432:5432 -e POSTGRES_USER=gomob -e POSTGRES_PASSWORD=gomob_dev -e POSTGRES_DB=gomob -v gomob-pg-data:/var/lib/postgresql/data postgres:16-alpine"
        echo "      podman run -d --name gomob-redis -p 6379:6379 -v gomob-redis-data:/data redis:7-alpine"
        echo "      podman run -d --name gomob-nats  -p 4222:4222 -p 8222:8222 nats:2-alpine"
        echo "      podman run -d --name gomob-minio -p 9000:9000 -p 9001:9001 -v gomob-minio-data:/data minio/minio server /data --console-address :9001"
        ((warn++))
    else
        local up=$(podman ps --format '{{.Names}}' | grep -cE '^gomob-(pg|redis|nats|minio)$')
        green "✓ dev 栈容器齐 (running=$up/4)；./dev.sh server up 可一键启动"
        ((ok++))
    fi
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
check_podman
check_protoc
check_git

echo
echo "汇总：${ok} 通过 / ${warn} 警告 / ${fail} 失败"

if [[ $fail -gt 0 ]]; then
    exit 1
fi
exit 0
