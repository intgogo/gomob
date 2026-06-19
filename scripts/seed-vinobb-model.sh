#!/usr/bin/env bash
# 把 yolo-obb（VIN 字符 OBB）模型播种进 model-registry，供生产 cv-engine 启动期从 registry→MinIO 拉取
# （与 VMASK 同机制；替掉 handler.go 的 .dev 硬编码兜底路径）。
#
# ⚠ 这是 ops 步骤，需要运行态基础设施（MinIO + model-registry 都在跑）；纯本地开发不必跑，
#   cv-engine 会用 VIN_OBB_MODEL / 默认 .dev 路径兜底（见 handler.go ensureVinObbModel）。
#
# 前置：mc(MinIO Client) + curl + jq + sha256sum。
# 流程：① 算 sha256 → ② mc 上传 onnx 到 gomob-assets → ③ POST /admin/v1/models(draft) → ④ activate。
# 跑完把 VINOBB 加进生产 cv-engine 的 GOMOB_CVENGINE_MODEL_NAMES（逗号分隔），重启即生效。
#
# 用法：
#   MODEL=.dev/vin_models/yolo-obb.onnx VERSION=v1 ./scripts/seed-vinobb-model.sh
# 可覆盖的环境变量见下方默认值。
set -euo pipefail

MODEL="${MODEL:-.dev/vin_models/yolo-obb.onnx}"
VERSION="${VERSION:-v1}"
NAME="${NAME:-VINOBB}"

# MinIO（与 loader.go / asset 服务同 bucket）
MINIO_ENDPOINT="${MINIO_ENDPOINT:-127.0.0.1:9000}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
BUCKET="${BUCKET:-gomob-assets}"
OBJECT_KEY="${OBJECT_KEY:-models/vinobb/yolo-obb-${VERSION}.onnx}"

# model-registry（loader 默认 127.0.0.1:18057）；admin 端点要 X-Gomob-Roles: admin
REGISTRY="${REGISTRY:-http://127.0.0.1:18057}"

for bin in mc curl jq sha256sum; do
  command -v "$bin" >/dev/null 2>&1 || { echo "缺 $bin，先装（mc=MinIO Client）"; exit 1; }
done
[ -f "$MODEL" ] || { echo "模型不存在：$MODEL"; exit 1; }

echo "== ① sha256 =="
SHA="$(sha256sum "$MODEL" | awk '{print $1}')"
SIZE="$(stat -c%s "$MODEL")"
echo "  $SHA  ($SIZE bytes)"

echo "== ② 上传 MinIO $BUCKET/$OBJECT_KEY =="
mc alias set gomobseed "http://${MINIO_ENDPOINT}" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null
mc cp "$MODEL" "gomobseed/${BUCKET}/${OBJECT_KEY}"

echo "== ③ 录入 draft（kind=com，std=1/255）=="
# metadata.kind=com → loader 走 core.RegisterComONNX（gocv.CreateORTCom）；std 缺省 1/255，写进来更明确。
# classes 仅文档用（com 返原始张量，后处理在 restore 包做），iwidth/iheight 由模型 input shape 自推。
META='{"kind":"com","std":0.00392156862745098,"classes":["number"],"note":"yolo11s-obb VIN 字符整串 OBB [1,6,8400]"}'
CREATE_BODY="$(jq -n \
  --arg name "$NAME" --arg ver "$VERSION" --arg uri "$OBJECT_KEY" --arg sha "$SHA" \
  --argjson meta "$META" \
  '{name:$name, version:$ver, asset_uri:$uri, sha256:$sha, runtime:"onnx", metadata:$meta}')"
RESP="$(curl -fsS -X POST "${REGISTRY}/admin/v1/models" \
  -H 'Content-Type: application/json' -H 'X-Gomob-Roles: admin' \
  -d "$CREATE_BODY")"
echo "  $RESP"
ID="$(echo "$RESP" | jq -r '.data.id // .id')"
[ -n "$ID" ] && [ "$ID" != "null" ] || { echo "拿不到新建模型 id"; exit 1; }

echo "== ④ 激活（同 name 旧 active 自动归档）=="
curl -fsS -X POST "${REGISTRY}/admin/v1/models/${ID}/activate" -H 'X-Gomob-Roles: admin' >/dev/null
echo "  activated id=$ID name=$NAME version=$VERSION"

cat <<EOF

✅ 完成。最后一步（生产 cv-engine）：把 $NAME 加进 GOMOB_CVENGINE_MODEL_NAMES 并重启，例如：
   GOMOB_CVENGINE_MODEL_NAMES="VMASK,VMET,$NAME"
启动期 loader 会从 registry→MinIO 拉 $NAME 并 RegisterComONNX；之后 vin_restore 不再用 .dev 兜底。
EOF
