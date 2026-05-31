#!/usr/bin/env bash
# scan_fusion_e2e — M3.14 云端融合全链路端到端 harness。
#   合成多视角 RGBD → RgbdShot bundle → MinIO → 入队 scan_fusion_jobs → fusionworker.ProcessOne
#   → POST fusion_service /fuse → GLB 存 MinIO → 断言 DB done + scan.fusion_done 事件 + GLB 几何 chamfer ≤ 5mm。
# 需:pg/nats/minio 容器(./dev.sh server up)、带 open3d/trimesh/fastapi 的 venv、go、migrate。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"
# shellcheck source=/dev/null
source "$ROOT/scripts/lib/dev-ports.sh"
export PATH="$PATH:/usr/lib/golang/bin:$HOME/go/bin"

PY="${FUSION_PY:-$ROOT/.dev/fusion-venv/bin/python}"
OUT="$ROOT/.dev/scan_fusion_e2e"
mkdir -p "$OUT"
export GOMOB_DB_DSN="${GOMOB_DB_DSN:-$GOMOB_DEFAULT_DB_DSN}"
export GOMOB_NATS_URL="${GOMOB_NATS_URL:-$GOMOB_DEFAULT_NATS_URL}"
export GOMOB_MINIO_ENDPOINT="${GOMOB_MINIO_ENDPOINT:-$GOMOB_DEFAULT_MINIO_ENDPOINT}"
export GOMOB_MINIO_BUCKET="${GOMOB_MINIO_BUCKET:-gomob-assets}"
export GOMOB_REDIS_ADDR="${GOMOB_REDIS_ADDR:-$GOMOB_DEFAULT_REDIS_ADDR}"
FUSION_PORT="${GOMOB_FUSION_PORT:-18092}"
export GOMOB_FUSION_URL="http://127.0.0.1:${FUSION_PORT}"

if [ ! -x "$PY" ] || ! "$PY" -c "import open3d, trimesh, fastapi" 2>/dev/null; then
  echo "✗ 缺带 open3d/trimesh/fastapi 的 venv:$PY" >&2; exit 2
fi

echo "== 1. 基础设施(pg/nats/minio)=="
./dev.sh server up >/dev/null 2>&1 || true

echo "== 2. 迁移 up(应用 0016_scan_fusion)=="
bash "$ROOT/server/scripts/migrate.sh" up >/dev/null

echo "== 3. 合成 bundle =="
export GOMOB_E2E_SESSION="e2e-$(date +%s)"
export E2E_BUNDLE_FILE="$OUT/bundle.zip"
export E2E_RESULT_FILE="$OUT/result.glb"
rm -f "$E2E_RESULT_FILE"
"$PY" "$ROOT/tests/harness/scan_fusion_e2e/prepare.py"

echo "== 4. 起 fusion_service(uvicorn :$FUSION_PORT)=="
( cd "$ROOT/server/fusion_service" && exec env GOMOB_FUSION_PORT="$FUSION_PORT" "$PY" app.py >"$OUT/fusion_service.log" 2>&1 ) &
SVC_PID=$!   # exec 后 SVC_PID 即 python 进程,trap kill 才能真正清掉(否则子 shell 死、python 逃逸)
cleanup() { kill "$SVC_PID" 2>/dev/null || true; }
trap cleanup EXIT
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${FUSION_PORT}/healthz" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$SVC_PID" 2>/dev/null; then echo "✗ fusion_service 退出,见 $OUT/fusion_service.log"; tail -20 "$OUT/fusion_service.log"; exit 1; fi
  sleep 1
done
echo "  fusion_service ready: $(curl -fsS http://127.0.0.1:${FUSION_PORT}/healthz)"

export GOMOB_E2E_BUNDLE_PATH="$E2E_BUNDLE_FILE"
export GOMOB_E2E_BUNDLE_KEY="scan_fusion_e2e/${GOMOB_E2E_SESSION}/bundle.zip"
export GOMOB_E2E_RESULT_PATH="$E2E_RESULT_FILE"

echo "== 5a. 真实上传 init/part/complete(kind=scan3d_bundle)→ 断言自动入队 =="
( cd "$ROOT/server" && go test -tags e2e_fusion ./internal/asset/ -run TestUploadBundleEnqueuesFusion -v -count=1 )

echo "== 5b. 入队 + worker 单步 + 断言 done/事件/GLB(go e2e)=="
( cd "$ROOT/server" && go test -tags e2e_fusion ./internal/fusion/ -run TestFusionE2E -v -count=1 )

echo "== 5c. signaling fusion bridge:经真 NATS 发 scan.fusion_done → owner ws 连接收到 =="
( cd "$ROOT/server" && go test -tags e2e_fusion ./internal/signaling/ -run TestFusionBridgeNATSRoundtrip -v -count=1 )

echo "== 6. GLB 几何复核(chamfer vs 观测面)=="
"$PY" "$ROOT/tests/harness/scan_fusion_e2e/verify.py"

echo ">>> scan_fusion_e2e 全链路通过"
