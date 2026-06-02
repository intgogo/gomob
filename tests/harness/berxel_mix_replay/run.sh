#!/usr/bin/env bash
# berxel_mix_replay —— 验证【自研 NATIVE_REWRITE 路径并发 color+depth(MIX 模式)】行为好不好。
#
# 为什么要这个 harness:color+depth 并发是涌现行为(两条流互相挤占 USB 带宽/master 控制通道),
#   且对序列/帧率参数敏感(StreamFlagMode、OpenStream 时序、companion reg0x19)。单测只能证"代码对",
#   这里证"原厂 MIX 配方在自研 libusb replay 路径上能稳定并发出帧 + 深度仍是 metric + 时间对齐 RGBD"。
#
# 配方来源:tests/harness/berxel_mix_trace 用 usbmon 抓原厂 SDK setStreamFlagMode(MIX)+startStreams(C|D)
#   的 definitive 序列 → 落成资产 iHawkP100R3_master_mix_init.json(21 条)+ companion_mix_init.json(8 条)。
#   berxel_host_probe 在 --color --depth 时自动选这两个 MIX 资产(与 Android startDualNative enableColor 同路)。
#
# 流程:① 检查相机在服务器;② 编+跑 host probe 并发拉流 N 秒;③ 存 RESULT/depth-first.raw/color-first.jpg;
#       ④ analyze.py 出可判定结论(正常/警告/异常)。
set -uo pipefail
PROJ="$(cd "$(dirname "$0")/../../.." && pwd)"; cd "$PROJ"
OUT="${OUTPUT_DIR:-$PROJ/.dev/berxel_mix_replay}"; mkdir -p "$OUT"
DUR_MS="${DUR_MS:-5000}"

echo "== 0. 前置:相机在服务器(NATIVE_REWRITE host 路径,非 Android)=="
lsusb -d 0603:001f >/dev/null 2>&1 || { echo "❌ master 0603:001f 不在服务器,接回再跑"; exit 2; }
lsusb -d 3558:1012 >/dev/null 2>&1 || { echo "❌ companion 3558:1012 不在"; exit 2; }
echo "✅ master + companion 在线"

echo "== 1. 编+跑 host probe 并发 color+depth(MIX 自动选资产)dur=${DUR_MS}ms =="
# 不传 --master-payloads/--companion-init:让 host probe 走与 Android enableColor 同样的 MIX 自动选路。
timeout 120 scripts/berxel-host-probe.sh \
    --depth --color --dur-ms "$DUR_MS" \
    --out-dir "$OUT" > "$OUT/run.log" 2>&1
RC=$?
echo "host probe exit=$RC(0/9 都可能带数据,以 analyze 为准)"

# 抽 RESULT 段单独存一份给 analyze
awk '/=== RESULT ===/{f=1} f{print}' "$OUT/run.log" > "$OUT/result.txt"
grep -iE "加载 master XU5 payload|加载 companion init|replay done|UVC committed|OpenStream payload:|keepalive (started|loop exit)" \
    "$OUT/run.log" > "$OUT/setup.txt" 2>/dev/null || true

echo "== 2. 分析 =="
python3 "$PROJ/tests/harness/berxel_mix_replay/analyze.py" "$OUT"
exit $?
