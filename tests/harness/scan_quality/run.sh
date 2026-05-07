#!/usr/bin/env bash
# scan_quality harness/run.sh — host 合成扫描序列 + 端到端跑 native 重建管线
#
# 输出：
#   .dev/scan-quality/<case>/  含 mesh.obj cloud.ply ground_truth.ply stats.json
#
# 后续：./tests/harness/scan_quality/analyze.py 读 .dev/scan-quality/ 输出健康度报告
#
# 当前 host-only 模式（合成深度无需真机）。M3.x 接 iHawk 真采时再加 capture 子命令。

set -euo pipefail
cd "$(dirname "$0")/../../.."

OUT_ROOT="${OUTPUT_DIR:-.dev/scan-quality}"
mkdir -p "$OUT_ROOT"

CXX_FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wno-deprecated-copy
           -Ithird_party/eigen-3.4.0 -Inative)

# 编译 runner
echo "==> compile runner"
g++ "${CXX_FLAGS[@]}" \
    tests/harness/scan_quality/runner.cpp \
    native/reconstruction/scan_session.cpp \
    native/reconstruction/icp.cpp \
    native/reconstruction/tsdf.cpp \
    native/reconstruction/marching_cubes.cpp \
    native/reconstruction/mesh_export.cpp \
    native/depth/depth_projection.cpp \
    -o "$OUT_ROOT/runner"

run_case() {
    local name=$1; shift
    echo "==> case $name $*"
    "$OUT_ROOT/runner" --out "$OUT_ROOT/case_$name" "$@"
}

# 5 个对照 case
run_case sphere_v4mm_f12  --voxel 4.0 --frames 12 --radius 60
run_case sphere_v2mm_f12  --voxel 2.0 --frames 12 --radius 60
run_case sphere_v4mm_f24  --voxel 4.0 --frames 24 --radius 60
run_case sphere_v5mm_f12  --voxel 5.0 --frames 12 --radius 60
run_case sphere_v4mm_r100 --voxel 4.0 --frames 12 --radius 100 --extent 320

# 分析阶段交给 dev.sh harness 钩子（python3 缺 numpy 时 analyze.py 会自己 exec 切到
# miniconda python）；本脚本只做"采样器"职责（CLAUDE.md harness 规范两段切分）。
