#!/usr/bin/env bash
# tail-user-logs.sh — 实时 tail 某用户的端侧上传日志
#
# 用法:
#   scripts/tail-user-logs.sh <user_id>           tail 今天的 jsonl
#   scripts/tail-user-logs.sh <user_id> <YYYY-MM-DD>  tail 指定日期
#   scripts/tail-user-logs.sh <user_id> | jq -r '.tag + " | " + .msg'   过滤格式化
#
# 服务端 GOMOB_LOG_UPLOAD_DIR (默认 .dev/server-logs/) 下结构: <user_id>/<YYYY-MM-DD>.jsonl

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "用法: $0 <user_id> [YYYY-MM-DD]" >&2
    exit 2
fi

USER_ID=$1
DATE="${2:-$(date -u +%Y-%m-%d)}"
ROOT="${GOMOB_LOG_UPLOAD_DIR:-.dev/server-logs}"
FILE="$ROOT/$USER_ID/$DATE.jsonl"

mkdir -p "$ROOT/$USER_ID"
[[ -f "$FILE" ]] || touch "$FILE"

echo "== tail $FILE ==" >&2
echo "== 提示: 加 | jq 格式化或 | grep <tag> 过滤特定 logcat tag ==" >&2
exec tail -F -n 50 "$FILE"
