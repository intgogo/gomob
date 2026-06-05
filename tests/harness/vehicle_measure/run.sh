#!/usr/bin/env bash
# vehicle_measure harness：对原厂真实会话点云复算车长/宽/高，与 Result.ini 真值比对。
# 真值源 = 逆向 JCHY 会话（docs/architecture/16）。默认 100742；JCHY_DATA 可覆盖会话目录。
set -euo pipefail
cd "$(dirname "$0")/../../.."   # → 仓库根

OUT_DIR=".dev/vehicle_measure"
mkdir -p "$OUT_DIR"
DATA="${JCHY_DATA:-/root/WindowsR/JCHY_OFFLINE/Data/100742}"
EIGEN="third_party/eigen-3.4.0"

[ -f "$EIGEN/Eigen/Dense" ] || { echo "Eigen 未投放: $EIGEN"; exit 1; }
if [ ! -f "$DATA/1.pcd" ] || [ ! -f "$DATA/Result.ini" ]; then
    echo "缺会话数据(需 1.pcd/2.pcd/Result.ini): $DATA"
    echo "设 JCHY_DATA=<会话目录> 指向原厂 Data/<id>/"
    exit 3
fi

echo "=== 构建 measure_cli (Eigen-only, 无 PCL) ==="
g++ -std=c++17 -O2 -Wall -Wextra -Inative -isystem "$EIGEN" \
    native/lidar/fusion.cpp native/lidar/io_pcd.cpp \
    native/measurement/preprocess.cpp native/measurement/dimensions.cpp \
    native/measurement/measure_vehicle.cpp \
    tests/harness/vehicle_measure/measure_cli.cpp \
    -o "$OUT_DIR/measure_cli"

echo "=== 测量 $DATA/{1,2}.pcd ==="
"$OUT_DIR/measure_cli" "$DATA/1.pcd" "$DATA/2.pcd" | tee "$OUT_DIR/measured.jsonl"

echo "=== 分析 vs Result.ini ==="
python3 tests/harness/vehicle_measure/analyze.py "$OUT_DIR/measured.jsonl" "$DATA/Result.ini"
