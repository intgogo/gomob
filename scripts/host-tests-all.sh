#!/usr/bin/env bash
# host-tests-all.sh — 聚合跑全部 native host 测试 runner,任一失败即非零退出。
#
# Why: 5 个 host-test runner(纯计算 / berxel / eys3d / lidar / measurement)此前零 CI 接入
# (写了不跑),单 runner 链接断裂或测试腐化无人察觉(如 eys3d-host-test 曾整条链接失败)。
# 本脚本是 native 自动门,由 `./dev.sh native-test` 与 `./dev.sh ci` 调用,提交前真跑真判。
set -uo pipefail
cd "$(dirname "$0")/.."

RUNNERS=(
    scripts/native-host-test.sh
    scripts/measurement-host-test.sh
    scripts/lidar-host-test.sh
    scripts/berxel-host-test.sh
    scripts/eys3d-host-test.sh
)

# 依赖前置:缺则 LOUD FAIL(非静默跳过 = 不假绿),让 CI 暴露缺依赖而非误判通过。
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERR: 缺依赖 '$1',native host 测试无法运行"; exit 2; }; }
need g++
need pkg-config
pkg-config --exists libusb-1.0 || { echo "ERR: 缺 libusb-1.0(berxel/eys3d host 测试需要)"; exit 2; }

fail=0
declare -a failed=()
for r in "${RUNNERS[@]}"; do
    echo "==================== $r ===================="
    if [[ ! -x "$r" && ! -f "$r" ]]; then
        echo "[FAIL] $r 不存在"; fail=1; failed+=("$r"); echo; continue
    fi
    if bash "$r"; then
        echo "[OK] $r"
    else
        echo "[FAIL] $r 退码非零"; fail=1; failed+=("$r")
    fi
    echo
done

echo "==================== 汇总 ===================="
if [[ $fail -eq 0 ]]; then
    echo "native host 测试全部通过(${#RUNNERS[@]} 个 runner)"
else
    echo "失败 runner: ${failed[*]}"
fi
exit $fail
