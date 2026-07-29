#!/usr/bin/env bash
# scan_bundle_roundtrip — schema v2 原始 VIN bundle ↔ 服务端 rgbd_bundle.unpack 契约校验。
#   roundtrip.py 生成真实 VINCreator BIN + 原始 disparity/RGB → unpack + fuse → 写 result.json;
#   analyze.py(stdlib,dev.sh 自动调)读 result.json 判定。
# 需带 open3d/PIL/numpy 的 Python(默认 .dev/fusion-venv;CI/容器用 fusion_service/requirements.txt)。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${FUSION_PY:-$ROOT/.dev/fusion-venv/bin/python}"
OUT="${OUTPUT_DIR:-$ROOT/.dev/scan_bundle_roundtrip}"

if [ ! -x "$PY" ] || ! "$PY" -c "import open3d, PIL, numpy" 2>/dev/null; then
  echo "缺少带 open3d/PIL/numpy 的 Python:$PY" >&2
  echo "建环境:python3.11 -m venv .dev/fusion-venv && .dev/fusion-venv/bin/pip install -r server/fusion_service/requirements.txt" >&2
  exit 2
fi

mkdir -p "$OUT"
"$PY" "$ROOT/tests/harness/scan_bundle_roundtrip/roundtrip.py" "$OUT"
