#!/usr/bin/env bash
# laser_background：准备离线分析目录；dev.sh 随后调用 analyze.py，不触发设备扫描或背景采集。
#
# 可选真数据输入（必须成套）：
#   LIVE_A_PCD / LIVE_B_PCD  本次 A/B 分设备原始云
#   BG_A_PCD / BG_B_PCD      background revision 的 A/B 区域裁剪设备系云
#   REGION_JSON               当前服务端区域点 JSON 文件或内联 JSON，不得携带旧 b_to_a
#   REGION_B_TO_A_JSON        canonical site B→A，定义背景相减的稳定裁剪域
#   FINAL_B_TO_A_JSON         本次任务最终 B→A，仅用于相减后合并 B 前景
# 历史兼容真数据输入（二选一，不能与上面混用）：
#   BACKGROUND_SCHEMA=legacy_fused
#   LIVE_PCD / BG_PCD         修改前已区域裁剪的 live/background 融合云
#   LEGACY_GROUND_JSON         持久地面 GroundPlane JSON
#   LEGACY_EXPECTED_DIMENSIONS_JSON  历史 L/W/H JSON
#   LEGACY_EXPECTED_FOREGROUND_POINTS / LEGACY_EXPECTED_BG_XYZ_SHA256
#   REQUIRE_REAL              默认 1；缺任一真数据或出现 warning 均返回非零
#                             仅分析器开发可显式设 0，输出不得作为现场验收
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/laser_background}"
mkdir -p "$OUT"
echo "已准备离线分析目录 $OUT（未触发设备扫描/背景采集）"

if [[ "${BACKGROUND_SCHEMA:-}" == "legacy_fused" ]]; then
    required=(LIVE_PCD BG_PCD LEGACY_GROUND_JSON LEGACY_EXPECTED_DIMENSIONS_JSON LEGACY_EXPECTED_FOREGROUND_POINTS LEGACY_EXPECTED_BG_XYZ_SHA256)
    missing=()
    for key in "${required[@]}"; do
        [[ -n "${!key:-}" ]] || missing+=("$key")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "legacy golden 输入不完整：${missing[*]}" >&2
        exit 1
    fi
    live_path="$(realpath "$LIVE_PCD")"
    background_path="$(realpath "$BG_PCD")"
    (
        cd "$ROOT/server"
        LEGACY_LIVE_PCD="$live_path" \
        LEGACY_BG_PCD="$background_path" \
        go test ./internal/laser -run '^TestLegacyFusedGoldenReplay$' -count=1 -v
    ) 2>&1 | tee "$OUT/legacy-go-replay.log"
fi
