#!/bin/bash
# scripts/lib/dev-ports.sh — gomob 开发栈宿主端口段单一真理源。
#
# 用法：在 dev.sh / harness run.sh / 任何需要连本机基础设施的脚本顶部
#       `source "$PROJ_DIR/scripts/lib/dev-ports.sh"` 即可。
#
# 容器内部端口（5432/6379/4222/9000/9001/8222）一律不动；只在宿主侧
# 把映射换到 5 位前缀段，避开服务器上其它产品默认端口。env 可覆盖。

GOMOB_PORT_PG="${GOMOB_PORT_PG:-15432}"
GOMOB_PORT_REDIS="${GOMOB_PORT_REDIS:-16379}"
GOMOB_PORT_NATS="${GOMOB_PORT_NATS:-14222}"
GOMOB_PORT_NATS_MON="${GOMOB_PORT_NATS_MON:-18222}"
GOMOB_PORT_MINIO="${GOMOB_PORT_MINIO:-19000}"
GOMOB_PORT_MINIO_CONSOLE="${GOMOB_PORT_MINIO_CONSOLE:-19001}"

# 派生的连接串 / endpoint —— harness 和 server 入口都用这套默认值。
GOMOB_DEFAULT_DB_DSN="${GOMOB_DEFAULT_DB_DSN:-postgres://gomob:gomob_dev@127.0.0.1:${GOMOB_PORT_PG}/gomob?sslmode=disable}"
GOMOB_DEFAULT_REDIS_ADDR="${GOMOB_DEFAULT_REDIS_ADDR:-127.0.0.1:${GOMOB_PORT_REDIS}}"
GOMOB_DEFAULT_NATS_URL="${GOMOB_DEFAULT_NATS_URL:-nats://127.0.0.1:${GOMOB_PORT_NATS}}"
GOMOB_DEFAULT_MINIO_ENDPOINT="${GOMOB_DEFAULT_MINIO_ENDPOINT:-127.0.0.1:${GOMOB_PORT_MINIO}}"
