#!/bin/bash
# 冷启动并恢复已有 latest，只验证渲染稳定性；脚本没有任何新建扫描入口。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
HARNESS_DIR="$PROJ_DIR/tests/harness/laser_render_stability"
OUT="${OUTPUT_DIR:-$PROJ_DIR/.dev/laser_render_stability}"
ADB="${ADB:-${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb}"
SERIAL="${LASER_RENDER_STABILITY_SERIAL:-${SERIAL:-${ADB_DEVICE:-emulator-5556}}}"
PKG="${LASER_RENDER_STABILITY_PACKAGE:-io.gomob.scan.debug}"
ACTIVITY="${LASER_RENDER_STABILITY_ACTIVITY:-io.gomob.scan.MainActivity}"
CYCLES="${LASER_RENDER_STABILITY_CYCLES:-20}"
DRAG_SEC="${LASER_RENDER_STABILITY_DRAG_SEC:-30}"
IDLE_SEC="${LASER_RENDER_STABILITY_IDLE_SEC:-10}"
RESTORE_TIMEOUT_SEC="${LASER_RENDER_STABILITY_RESTORE_TIMEOUT_SEC:-180}"
MODE_TIMEOUT_SEC="${LASER_RENDER_STABILITY_MODE_TIMEOUT_SEC:-20}"

mkdir -p "$OUT"
rm -rf "$OUT/ui" "$OUT/gfxinfo" "$OUT/threads" "$OUT/cpu"
rm -f "$OUT/run.json" "$OUT/run.log" "$OUT/logcat.txt" "$OUT/logcat-final.txt" \
    "$OUT/cycles.jsonl" "$OUT/reverse.txt" "$OUT/start.txt" "$OUT/device.txt" \
    "$OUT/uiautomator.log" "$OUT/activity.txt" "$OUT/backend-health.json" "$OUT/drag.txt" \
    "$OUT/exit-race.json" "$OUT/exit-race-logcat.txt" "$OUT/exit-race-gfxinfo.txt" \
    "$OUT/exit-race-start.txt"
mkdir -p "$OUT/ui" "$OUT/gfxinfo" "$OUT/threads" "$OUT/cpu"
: > "$OUT/cycles.jsonl"

ADB_CMD=("$ADB" -s "$SERIAL")
LOGCAT_PID=""
RACE_LOGCAT_PID=""
RUN_ERROR=""
PHASE="初始化"
RUN_COMPLETE=false
CYCLES_COMPLETED=0
START_EPOCH_MS="$(date +%s%3N)"
BACKEND_HEALTH=false

log() {
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"
}

current_pid() {
    "${ADB_CMD[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'
}

dump_ui() {
    local output="$1"
    local remote="/sdcard/gomob-laser-render-stability.xml"
    if ! timeout 15s "${ADB_CMD[@]}" shell uiautomator dump --compressed "$remote" \
        >> "$OUT/uiautomator.log" 2>&1; then
        return 1
    fi
    if ! "${ADB_CMD[@]}" exec-out cat "$remote" > "$output" 2>> "$OUT/uiautomator.log"; then
        return 1
    fi
    python3 "$HARNESS_DIR/ui_query.py" summary "$output" >/dev/null 2>&1
}

wait_ui() {
    local condition="$1" timeout_sec="$2" output="$3"
    local deadline=$(( $(date +%s) + timeout_sec ))
    local probe="$OUT/ui/.probe.xml"
    while (( $(date +%s) <= deadline )); do
        if dump_ui "$probe" && python3 "$HARNESS_DIR/ui_query.py" check "$probe" "$condition" \
            > "$OUT/ui/.last-check.json" 2>/dev/null; then
            cp "$probe" "$output"
            return 0
        fi
        sleep 1
    done
    [[ -s "$probe" ]] && cp "$probe" "$output"
    return 1
}

tap_text_from_ui() {
    local text="$1" source_ui="$2"
    local point x y
    point="$(python3 "$HARNESS_DIR/ui_query.py" point "$source_ui" text "$text" 2>> "$OUT/uiautomator.log")" \
        || return 1
    read -r x y <<< "$point"
    log "点击：$text ($x,$y)"
    "${ADB_CMD[@]}" shell input tap "$x" "$y" >/dev/null 2>&1
}

reset_gfx() {
    timeout 20s "${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
}

collect_gfx() {
    local name="$1"
    timeout 20s "${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" framestats \
        > "$OUT/gfxinfo/$name.txt" 2>&1 || true
}

collect_threads() {
    local name="$1"
    local pid comms task_count fengine_count
    pid="$(current_pid)"
    if [[ -z "$pid" ]]; then
        printf 'pid=\ntask_count=0\nfengine_loop_count=0\n' > "$OUT/threads/$name.txt"
        return 1
    fi
    task_count="$("${ADB_CMD[@]}" shell "ls /proc/$pid/task 2>/dev/null | wc -l" | tr -d '\r ')"
    comms="$("${ADB_CMD[@]}" shell \
        "for f in /proc/$pid/task/*/comm; do cat \"\$f\" 2>/dev/null; done" 2>/dev/null | tr -d '\r')"
    fengine_count="$(printf '%s\n' "$comms" | grep -cx 'FEngine::loop' || true)"
    {
        printf 'pid=%s\n' "$pid"
        printf 'task_count=%s\n' "${task_count:-0}"
        printf 'fengine_loop_count=%s\n' "${fengine_count:-0}"
        printf '%s\n' "$comms"
        printf '\n-- ps -T --\n'
        "${ADB_CMD[@]}" shell ps -T -p "$pid" 2>/dev/null | tr -d '\r' || true
    } > "$OUT/threads/$name.txt"
}

abort_run() {
    RUN_ERROR="$*"
    log "采样中止：$RUN_ERROR"
    exit 0
}

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM
    if [[ $exit_code -ne 0 && -z "$RUN_ERROR" ]]; then
        RUN_ERROR="采样脚本异常退出：$exit_code"
    fi
    if [[ -n "$LOGCAT_PID" ]]; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
    if [[ -n "$RACE_LOGCAT_PID" ]]; then
        kill "$RACE_LOGCAT_PID" 2>/dev/null || true
        wait "$RACE_LOGCAT_PID" 2>/dev/null || true
    fi
    if [[ -x "$ADB" ]] && "${ADB_CMD[@]}" get-state >/dev/null 2>&1; then
        "${ADB_CMD[@]}" logcat -b all -d -v threadtime > "$OUT/logcat-final.txt" 2>&1 || true
        "${ADB_CMD[@]}" shell dumpsys activity activities > "$OUT/activity.txt" 2>&1 || true
    fi
    local end_epoch_ms
    end_epoch_ms="$(date +%s%3N)"
    python3 - "$OUT/run.json" "$SERIAL" "$PKG" "$PHASE" "$RUN_COMPLETE" "$RUN_ERROR" \
        "$CYCLES" "$CYCLES_COMPLETED" "$DRAG_SEC" "$IDLE_SEC" "$RESTORE_TIMEOUT_SEC" \
        "$START_EPOCH_MS" "$end_epoch_ms" "$BACKEND_HEALTH" <<'PY'
import json
import sys

(
    path, serial, package, phase, complete, error, expected_cycles, completed_cycles,
    drag_sec, idle_sec, restore_timeout_sec, started_ms, ended_ms, backend_health,
) = sys.argv[1:]
data = {
    "serial": serial,
    "package": package,
    "phase": phase,
    "complete": complete == "true",
    "error": error,
    "expected_cycles": int(expected_cycles),
    "completed_cycles": int(completed_cycles),
    "drag_sec": int(drag_sec),
    "idle_sec": int(idle_sec),
    "restore_timeout_sec": int(restore_timeout_sec),
    "started_epoch_ms": int(started_ms),
    "ended_epoch_ms": int(ended_ms),
    "duration_sec": (int(ended_ms) - int(started_ms)) / 1000.0,
    "backend_health": backend_health == "true",
    "expected": {
        "fused_source_points": 2050753,
        "fused_render_points": 262144,
        "unit_render_points": 65536,
        "dimension_labels": 0,
        "dimension_badges": 0,
        "wireframe_nodes": 1,
        "engines": 3,
    },
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, ensure_ascii=False, indent=2)
PY
    log "采样结束：phase=$PHASE complete=$RUN_COMPLETE output=$OUT"
}

trap cleanup EXIT
trap 'RUN_ERROR="采样被信号中断"; exit 0' INT TERM

PHASE="前置检查"
[[ "$CYCLES" =~ ^[1-9][0-9]*$ ]] || abort_run "CYCLES 必须为正整数"
[[ "$DRAG_SEC" =~ ^[1-9][0-9]*$ ]] || abort_run "DRAG_SEC 必须为正整数"
[[ "$IDLE_SEC" =~ ^[0-9]+$ ]] || abort_run "IDLE_SEC 必须为非负整数"
[[ -x "$ADB" ]] || abort_run "adb 不存在：$ADB"
"${ADB_CMD[@]}" get-state >/dev/null 2>&1 || abort_run "设备未在线：$SERIAL"
"${ADB_CMD[@]}" shell pm path "$PKG" >/dev/null 2>&1 || abort_run "App 未安装：$PKG"

{
    printf 'serial=%s\n' "$SERIAL"
    printf 'model=%s\n' "$("${ADB_CMD[@]}" shell getprop ro.product.model | tr -d '\r')"
    printf 'sdk=%s\n' "$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
    printf 'size=%s\n' "$("${ADB_CMD[@]}" shell wm size | tr -d '\r')"
} > "$OUT/device.txt"

if curl -fsS --max-time 3 http://127.0.0.1:18808/healthz > "$OUT/backend-health.json" 2>&1; then
    BACKEND_HEALTH=true
else
    log "警告：host 18808 健康检查失败，仍继续验证 App 的真实恢复结果"
fi

"${ADB_CMD[@]}" reverse tcp:8808 tcp:18808 > "$OUT/reverse.txt" 2>&1 \
    || abort_run "adb reverse tcp:8808→18808 失败"
"${ADB_CMD[@]}" reverse tcp:18808 tcp:18808 >> "$OUT/reverse.txt" 2>&1 \
    || abort_run "adb reverse tcp:18808→18808 失败"
"${ADB_CMD[@]}" reverse --list >> "$OUT/reverse.txt" 2>&1 || true

"${ADB_CMD[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"${ADB_CMD[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true

PHASE="分镜初始化立即退出"
log "验证融合 Surface 已建立、A/B Engine 初始化时立即返回"
"${ADB_CMD[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 1
"${ADB_CMD[@]}" logcat -b all -c >/dev/null 2>&1 || abort_run "退出竞态前清空 logcat 失败"
timeout 20s "${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
"${ADB_CMD[@]}" logcat -b all -v threadtime > "$OUT/exit-race-logcat.txt" 2>&1 &
RACE_LOGCAT_PID=$!
"${ADB_CMD[@]}" shell am start -W -n "$PKG/$ACTIVITY" > "$OUT/exit-race-start.txt" 2>&1 \
    || abort_run "退出竞态 MainActivity 启动失败"
for _ in $(seq 1 30); do
    if dump_ui "$OUT/ui/exit-race-home.xml" && python3 "$HARNESS_DIR/ui_query.py" point \
        "$OUT/ui/exit-race-home.xml" text "3D" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
tap_text_from_ui "3D" "$OUT/ui/exit-race-home.xml" \
    || abort_run "退出竞态找不到底部 3D 入口"
wait_ui root3d 30 "$OUT/ui/exit-race-root3d.xml" || abort_run "退出竞态进入 3D 根页超时"
tap_text_from_ui "车辆外廓扫描" "$OUT/ui/exit-race-root3d.xml" \
    || abort_run "退出竞态找不到车辆外廓入口"
wait_ui completed "$RESTORE_TIMEOUT_SEC" "$OUT/ui/exit-race-fused.xml" \
    || abort_run "退出竞态 latest 恢复超时"
race_start_ms="$(date +%s%3N)"
tap_text_from_ui "分镜" "$OUT/ui/exit-race-fused.xml" || abort_run "退出竞态无法点击分镜"
"${ADB_CMD[@]}" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 \
    || abort_run "退出竞态返回键注入失败"
race_responded=false
if wait_ui root3d 12 "$OUT/ui/exit-race-after-back.xml"; then
    race_responded=true
fi
race_end_ms="$(date +%s%3N)"
sleep 6
timeout 20s "${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" framestats \
    > "$OUT/exit-race-gfxinfo.txt" 2>&1 || true
kill "$RACE_LOGCAT_PID" 2>/dev/null || true
wait "$RACE_LOGCAT_PID" 2>/dev/null || true
RACE_LOGCAT_PID=""
python3 - "$OUT/exit-race.json" "$race_responded" "$race_start_ms" "$race_end_ms" <<'PY'
import json
import sys

path, responded, started_ms, ended_ms = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "responded": responded == "true",
            "roundtrip_ms": int(ended_ms) - int(started_ms),
        },
        handle,
        ensure_ascii=False,
        indent=2,
    )
PY
[[ "$race_responded" == true ]] || abort_run "A/B Engine 初始化时返回后 12 秒仍未恢复"

"${ADB_CMD[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 1
"${ADB_CMD[@]}" logcat -b all -c >/dev/null 2>&1 || abort_run "清空 logcat 失败"
timeout 20s "${ADB_CMD[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
"${ADB_CMD[@]}" logcat -b all -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

PHASE="冷启动"
log "冷启动 $PKG（只恢复已有 latest，不清数据）"
"${ADB_CMD[@]}" shell am start -W -n "$PKG/$ACTIVITY" > "$OUT/start.txt" 2>&1 \
    || abort_run "MainActivity 启动失败"

for _ in $(seq 1 30); do
    [[ -n "$(current_pid)" ]] && break
    sleep 1
done
[[ -n "$(current_pid)" ]] || abort_run "冷启动后 30 秒内没有 App PID"

PHASE="进入车辆外廓"
for _ in $(seq 1 30); do
    if dump_ui "$OUT/ui/home.xml" && python3 "$HARNESS_DIR/ui_query.py" point \
        "$OUT/ui/home.xml" text "3D" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
tap_text_from_ui "3D" "$OUT/ui/home.xml" \
    || abort_run "找不到底部 3D 入口；可能停在登录页或异常弹窗"
wait_ui root3d 30 "$OUT/ui/3d_root.xml" || abort_run "进入 3D 根页超时"
tap_text_from_ui "车辆外廓扫描" "$OUT/ui/3d_root.xml" || abort_run "找不到车辆外廓入口"

PHASE="恢复 latest 完成态"
log "等待已有 latest 恢复完成，并出现尺寸线框与 13 条结果"
wait_ui completed "$RESTORE_TIMEOUT_SEC" "$OUT/ui/restore_fused.xml" \
    || abort_run "latest 未在 ${RESTORE_TIMEOUT_SEC}s 内恢复为指定完成态"
collect_threads restore_fused || abort_run "恢复完成后 App PID 消失"
collect_gfx restore

PHASE="尺寸开关"
reset_gfx
tap_text_from_ui "尺寸叠加" "$OUT/ui/restore_fused.xml" || abort_run "找不到尺寸叠加开关"
wait_ui overlay_off "$MODE_TIMEOUT_SEC" "$OUT/ui/overlay_off.xml" \
    || abort_run "尺寸关闭后标签未变为 0"
tap_text_from_ui "尺寸叠加" "$OUT/ui/overlay_off.xml" || abort_run "无法重新开启尺寸叠加"
wait_ui fused "$MODE_TIMEOUT_SEC" "$OUT/ui/overlay_on.xml" \
    || abort_run "尺寸重新开启后标签未恢复为 11"
collect_threads overlay_on || abort_run "尺寸开关后 App PID 消失"
collect_gfx overlay_toggle

PHASE="首次创建分镜"
reset_gfx
tap_text_from_ui "分镜" "$OUT/ui/overlay_on.xml" || abort_run "无法进入分镜"
wait_ui storyboard "$MODE_TIMEOUT_SEC" "$OUT/ui/storyboard_first.xml" \
    || abort_run "首次分镜未显示 A/B 各 65,536 点"
collect_threads storyboard_first || abort_run "首次分镜后 App PID 消失"
tap_text_from_ui "融合" "$OUT/ui/storyboard_first.xml" || abort_run "首次分镜后无法返回融合"
wait_ui fused "$MODE_TIMEOUT_SEC" "$OUT/ui/fused_after_storyboard.xml" \
    || abort_run "首次分镜后融合态未恢复"
collect_gfx storyboard_warmup

PHASE="融合分镜循环"
reset_gfx
log "开始 $CYCLES 轮融合↔分镜切换"
cp "$OUT/ui/fused_after_storyboard.xml" "$OUT/ui/cycle-current.xml"
for cycle in $(seq 1 "$CYCLES"); do
    cycle_start="$(date +%s%3N)"
    tap_text_from_ui "分镜" "$OUT/ui/cycle-current.xml" || abort_run "第 $cycle 轮无法点击分镜"
    wait_ui storyboard "$MODE_TIMEOUT_SEC" "$OUT/ui/cycle-current.xml" \
        || abort_run "第 $cycle 轮分镜未就绪"
    storyboard_ready="$(date +%s%3N)"
    tap_text_from_ui "融合" "$OUT/ui/cycle-current.xml" || abort_run "第 $cycle 轮无法点击融合"
    wait_ui fused "$MODE_TIMEOUT_SEC" "$OUT/ui/cycle-current.xml" \
        || abort_run "第 $cycle 轮融合未就绪"
    fused_ready="$(date +%s%3N)"
    CYCLES_COMPLETED=$cycle
    printf '{"cycle":%d,"storyboard_ms":%d,"fused_ms":%d,"total_ms":%d,"ok":true}\n' \
        "$cycle" "$((storyboard_ready - cycle_start))" "$((fused_ready - storyboard_ready))" \
        "$((fused_ready - cycle_start))" >> "$OUT/cycles.jsonl"
done
dump_ui "$OUT/ui/after_cycles.xml" || abort_run "20 轮后 UI dump 失败"
python3 "$HARNESS_DIR/ui_query.py" check "$OUT/ui/after_cycles.xml" fused >/dev/null \
    || abort_run "20 轮后未停留在完整融合态"
collect_threads after_cycles || abort_run "20 轮后 App PID 消失"
collect_gfx mode_cycles

PHASE="融合点云连续拖动"
reset_gfx
size="$("${ADB_CMD[@]}" shell wm size | tr -d '\r' | sed -n 's/.*size: \([0-9][0-9]*x[0-9][0-9]*\).*/\1/p' | tail -1)"
[[ "$size" =~ ^([0-9]+)x([0-9]+)$ ]] || abort_run "无法解析设备分辨率：$size"
width="${BASH_REMATCH[1]}"
height="${BASH_REMATCH[2]}"
x1=$((width * 25 / 100))
x2=$((width * 75 / 100))
y1=$((height * 35 / 100))
y2=$((height * 58 / 100))
log "在融合点云区域连续拖动 ${DRAG_SEC}s：($x1,$y1)→($x2,$y2)"
"${ADB_CMD[@]}" shell input touchscreen swipe "$x1" "$y1" "$x2" "$y2" "$((DRAG_SEC * 1000))" \
    > "$OUT/drag.txt" 2>&1 || abort_run "连续拖动命令失败"
wait_ui fused "$MODE_TIMEOUT_SEC" "$OUT/ui/after_drag.xml" \
    || abort_run "连续拖动后融合页未响应"
collect_threads after_drag || abort_run "连续拖动后 App PID 消失"
collect_gfx drag

PHASE="静置"
reset_gfx
log "静置 ${IDLE_SEC}s，不发送任何输入"
sleep "$IDLE_SEC"
collect_gfx idle_quiet
wait_ui fused "$MODE_TIMEOUT_SEC" "$OUT/ui/after_idle.xml" \
    || abort_run "静置后融合页未响应"
collect_threads after_idle || abort_run "静置后 App PID 消失"
collect_gfx idle_response
pid="$(current_pid)"
"${ADB_CMD[@]}" shell dumpsys meminfo "$pid" > "$OUT/cpu/meminfo.txt" 2>&1 || true
"${ADB_CMD[@]}" shell dumpsys cpuinfo > "$OUT/cpu/cpuinfo.txt" 2>&1 || true

PHASE="完成"
RUN_COMPLETE=true
log "全部动作采样完成；App 保持在融合页面"
