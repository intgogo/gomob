#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINK="$ROOT/.codegraph"
DATA_DIR="$ROOT/.dev/codegraph"

if ! command -v codegraph >/dev/null 2>&1; then
  echo "错误: 未找到 codegraph CLI; 请先安装: npm install -g codegraph" >&2
  exit 1
fi

ensure_layout() {
  mkdir -p "$DATA_DIR"

  if [[ -L "$LINK" ]]; then
    return
  fi

  if [[ -e "$LINK" ]]; then
    echo "错误: $LINK 已存在但不是软链; 请先手动迁移到 $DATA_DIR" >&2
    exit 1
  fi

  ln -s ".dev/codegraph" "$LINK"
}

usage() {
  cat <<'USAGE'
用法:
  scripts/codegraph.sh init
  scripts/codegraph.sh index [--force]
  scripts/codegraph.sh sync
  scripts/codegraph.sh status
  scripts/codegraph.sh query <关键词> [codegraph query 参数]
  scripts/codegraph.sh files [codegraph files 参数]
  scripts/codegraph.sh callers|callees|impact <符号> [参数]
  scripts/codegraph.sh serve --mcp [参数]

索引数据库固定写入 .dev/codegraph/，根目录 .codegraph 是软链。
USAGE
}

ensure_layout

cmd="${1:-status}"
shift || true

case "$cmd" in
  init|index|sync|status)
    exec codegraph "$cmd" "$ROOT" "$@"
    ;;
  query|files|callers|callees|impact|affected|serve)
    exec codegraph "$cmd" -p "$ROOT" "$@"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    exec codegraph "$cmd" "$@"
    ;;
esac
