#!/bin/bash
# migrate.sh — 跑 PG migrations（基于 golang-migrate）
#
# 用法:
#   ./scripts/migrate.sh up [N]          应用所有 / 前 N 步迁移
#   ./scripts/migrate.sh down [N]        回滚所有 / 前 N 步
#   ./scripts/migrate.sh status          看当前 version
#   ./scripts/migrate.sh force <ver>     强制设置版本（解决 dirty 状态）
#
# 环境变量:
#   GOMOB_DB_DSN  PG 连接串；缺省 postgres://gomob:gomob_dev@127.0.0.1:5432/gomob?sslmode=disable

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIGRATIONS_DIR="$ROOT/migrations"
DB_DSN="${GOMOB_DB_DSN:-postgres://gomob:gomob_dev@127.0.0.1:5432/gomob?sslmode=disable}"

resolve_migrate() {
    if command -v migrate >/dev/null 2>&1; then
        echo migrate
        return
    fi
    local goinstall_bin="${GOPATH:-$HOME/go}/bin/migrate"
    if [[ -x "$goinstall_bin" ]]; then
        echo "$goinstall_bin"
        return
    fi
    echo "✗ 未找到 migrate 二进制" >&2
    echo "  装：go install github.com/golang-migrate/migrate/v4/cmd/migrate@latest" >&2
    echo "  或下载：https://github.com/golang-migrate/migrate/releases" >&2
    exit 1
}

MIGRATE=$(resolve_migrate)

cmd="${1:-up}"; shift || true

case "$cmd" in
    up)
        if [[ -n "${1:-}" ]]; then
            "$MIGRATE" -path "$MIGRATIONS_DIR" -database "$DB_DSN" up "$1"
        else
            "$MIGRATE" -path "$MIGRATIONS_DIR" -database "$DB_DSN" up
        fi
        ;;
    down)
        if [[ -n "${1:-}" ]]; then
            "$MIGRATE" -path "$MIGRATIONS_DIR" -database "$DB_DSN" down "$1"
        else
            "$MIGRATE" -path "$MIGRATIONS_DIR" -database "$DB_DSN" down -all
        fi
        ;;
    status|version)
        "$MIGRATE" -path "$MIGRATIONS_DIR" -database "$DB_DSN" version
        ;;
    force)
        ver="${1:?force 需要 <version>}"
        "$MIGRATE" -path "$MIGRATIONS_DIR" -database "$DB_DSN" force "$ver"
        ;;
    *)
        echo "用法: $0 {up [N] | down [N] | status | force <ver>}" >&2
        exit 2
        ;;
esac
