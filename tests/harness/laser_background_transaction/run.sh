#!/usr/bin/env bash
# 在一次性 PostgreSQL 数据库中验证背景 active 指针与任务终态的原子性。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/laser_background_transaction}"
PG_CONTAINER="${GOMOB_TEST_PG_CONTAINER:-gomob-pg}"
PG_PORT="${GOMOB_TEST_PG_PORT:-15432}"
DB_NAME="gomob_laser_bg_tx_$(date +%s)_$$"
DB_DSN="postgres://gomob:gomob_dev@127.0.0.1:${PG_PORT}/${DB_NAME}?sslmode=disable"
MIGRATE="${MIGRATE_BIN:-${GOPATH:-$HOME/go}/bin/migrate}"
mkdir -p "$OUT"

cleanup() {
    podman exec "$PG_CONTAINER" psql -U gomob -d postgres -v ON_ERROR_STOP=1 \
        -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)" \
        >>"$OUT/database.log" 2>&1 || true
}
trap cleanup EXIT INT TERM

if [[ ! -x "$MIGRATE" ]]; then
    echo "异常：未找到 migrate：$MIGRATE" >&2
    exit 1
fi
if ! podman ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    echo "异常：PostgreSQL 容器未运行：$PG_CONTAINER" >&2
    exit 1
fi

podman exec "$PG_CONTAINER" psql -U gomob -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE \"$DB_NAME\"" >"$OUT/database.log" 2>&1
"$MIGRATE" -path "$ROOT/server/migrations" -database "$DB_DSN" up \
    >"$OUT/migrate.log" 2>&1

set +e
(
    cd "$ROOT/server"
    GOMOB_TEST_DB_DSN="$DB_DSN" go test ./pkg/repo \
        -run '^TestActivateAndCompletePostgresTransactions$' -count=1 -v
) 2>&1 | tee "$OUT/go-test.log"
status=${PIPESTATUS[0]}
set -e

if [[ $status -ne 0 ]]; then
    echo "异常：PostgreSQL 背景事务测试失败，见 $OUT/go-test.log" >&2
    exit "$status"
fi
echo "采样完成 → $OUT/go-test.log"
