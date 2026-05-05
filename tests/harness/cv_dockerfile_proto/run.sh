#!/bin/bash
# cv_dockerfile_proto/run.sh — M-S10.6 Dockerfile + cvengine.proto 静态校验
#
# 当前主机无 protoc / docker，本 harness 不真实构建镜像，只静态校验：
#   - server/Dockerfile.cvengine 存在 + FROM 指令两段构建（builder + runtime）
#   - 含 OpenCV / ONNX Runtime / libccv 三套 .so 的 COPY / ldconfig
#   - server/proto/cvengine.proto 存在 + go_package 字段正确 + service CVEngine 定义
#   - server/scripts/proto-gen.sh 能识别 cvengine.proto（list 模式）
#
# 真镜像构建留给 CI（独立 cv-engine workflow）；这里只验"契约文件"完整。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_dockerfile_proto}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=$1 ok=$2 note=${3:-}
    python3 -c "
import json
print(json.dumps({
    'scenario': '$scenario',
    'ok': '$ok' == 'true',
    'http_code': 0,
    'expected_http': 0,
    'code': None,
    'expected_code': None,
    'latency_ms': 0,
    'note': '''$note''',
}))
" >> "$RESULTS"
}

# ============================================================================
# A. Dockerfile.cvengine
# ============================================================================
log "A. Dockerfile.cvengine"
DF="$SERVER_DIR/Dockerfile.cvengine"
[[ -f "$DF" ]] && record "A1.dockerfile_exists" true "$(stat -c%s $DF)B" || record "A1.dockerfile_exists" false ""

# 两段构建
BUILDER_OK=$(grep -c "^FROM .* AS builder" "$DF" 2>/dev/null || echo 0)
RUNTIME_OK=$(grep -c "^FROM .* AS runtime" "$DF" 2>/dev/null || echo 0)
[[ "$BUILDER_OK" -ge 1 && "$RUNTIME_OK" -ge 1 ]] && record "A2.multi_stage_builder_runtime" true "builder=$BUILDER_OK runtime=$RUNTIME_OK" || record "A2.multi_stage_builder_runtime" false ""

# OpenCV 编译来源（builder 阶段）
HAS_OPENCV=$(grep -c "opencv" "$DF" 2>/dev/null || echo 0)
[[ "$HAS_OPENCV" -ge 2 ]] && record "A3.opencv_install" true "opencv 出现 $HAS_OPENCV 次" || record "A3.opencv_install" false ""

# ONNX Runtime
HAS_ONNX=$(grep -c "onnxruntime" "$DF" 2>/dev/null || echo 0)
[[ "$HAS_ONNX" -ge 2 ]] && record "A4.onnxruntime_install" true "onnxruntime 出现 $HAS_ONNX 次" || record "A4.onnxruntime_install" false ""

# libccv (内部仓自带 .so)
HAS_CCV=$(grep -c "libccv" "$DF" 2>/dev/null || echo 0)
[[ "$HAS_CCV" -ge 1 ]] && record "A5.libccv_copy" true "libccv 出现 $HAS_CCV 次" || record "A5.libccv_copy" false ""

# LD_LIBRARY_PATH 在 runtime 阶段设置
HAS_LD=$(grep -c "LD_LIBRARY_PATH" "$DF" 2>/dev/null || echo 0)
[[ "$HAS_LD" -ge 1 ]] && record "A6.ld_library_path" true "LD_LIBRARY_PATH 出现 $HAS_LD 次" || record "A6.ld_library_path" false ""

# HEALTHCHECK
HAS_HEALTH=$(grep -c "HEALTHCHECK" "$DF" 2>/dev/null || echo 0)
[[ "$HAS_HEALTH" -ge 1 ]] && record "A7.healthcheck" true "" || record "A7.healthcheck" false ""

# EXPOSE 端口
HAS_EXPOSE=$(grep -c "EXPOSE 18810" "$DF" 2>/dev/null || echo 0)
[[ "$HAS_EXPOSE" -ge 1 ]] && record "A8.expose_18810" true "" || record "A8.expose_18810" false ""

# ============================================================================
# B. cvengine.proto
# ============================================================================
log "B. cvengine.proto"
PB="$SERVER_DIR/proto/cvengine.proto"
[[ -f "$PB" ]] && record "B1.proto_exists" true "$(stat -c%s $PB)B" || record "B1.proto_exists" false ""

# proto3 syntax
HAS_SYNTAX=$(grep -c '^syntax = "proto3";' "$PB" 2>/dev/null || echo 0)
[[ "$HAS_SYNTAX" -ge 1 ]] && record "B2.syntax_proto3" true "" || record "B2.syntax_proto3" false ""

# go_package 正确
HAS_GOPKG=$(grep -c 'go_package = "io.gomob/server/proto/cvengine' "$PB" 2>/dev/null || echo 0)
[[ "$HAS_GOPKG" -ge 1 ]] && record "B3.go_package_correct" true "" || record "B3.go_package_correct" false ""

# service CVEngine
HAS_SERVICE=$(grep -c '^service CVEngine' "$PB" 2>/dev/null || echo 0)
[[ "$HAS_SERVICE" -ge 1 ]] && record "B4.service_cvengine" true "" || record "B4.service_cvengine" false ""

# 四个核心 RPC
RPC_COUNT=$(grep -E "^  rpc (VinCharacterCompare|VinCharacterCompareWithRef|VinDetectYolo|ListModels|Health)" "$PB" 2>/dev/null | wc -l)
[[ "$RPC_COUNT" -ge 5 ]] && record "B5.five_rpcs_defined" true "RPC 定义=$RPC_COUNT" || record "B5.five_rpcs_defined" false "RPC 定义=$RPC_COUNT"

# FontDistMethod enum
HAS_ENUM=$(grep -c '^enum FontDistMethod' "$PB" 2>/dev/null || echo 0)
[[ "$HAS_ENUM" -ge 1 ]] && record "B6.font_dist_enum" true "" || record "B6.font_dist_enum" false ""

# ============================================================================
# C. proto-gen.sh 识别
# ============================================================================
log "C. proto-gen.sh"
GEN="$SERVER_DIR/scripts/proto-gen.sh"
[[ -x "$GEN" ]] && record "C1.proto_gen_executable" true "" || record "C1.proto_gen_executable" false ""

# 在缺 protoc 时，脚本应明确报错（不能静默通过）
GEN_OUT=$("$GEN" 2>&1 || true)
if echo "$GEN_OUT" | grep -q "缺 protoc"; then
    record "C2.proto_gen_reports_missing_protoc" true "脚本明确报缺 protoc"
elif echo "$GEN_OUT" | grep -q "ready"; then
    # protoc 存在 + 跑通
    record "C2.proto_gen_reports_missing_protoc" true "protoc 已就绪 + 编译完成"
else
    record "C2.proto_gen_reports_missing_protoc" false "意外输出: ${GEN_OUT:0:100}"
fi

log "采样完成 → $RESULTS"
