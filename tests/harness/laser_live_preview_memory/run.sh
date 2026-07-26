#!/usr/bin/env bash
# 真实双激光扫描内存闭环：从 App UI 起扫，跨过历史 44 秒 OOM 窗口后安全停止。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/laser_live_preview_memory}"
ADB_BIN="${ADB:-/opt/android-sdk/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:-${ADB_DEVICE:-emulator-5556}}"
PKG="io.gomob.scan.debug"
ACTIVITY="$PKG/io.gomob.scan.MainActivity"
GATEWAY="${GOMOB_GATEWAY_URL:-http://127.0.0.1:18808}"
UNIT_A_IP="${GOMOB_LASER_UNIT_A_IP:-192.168.9.101}"
UNIT_B_IP="${GOMOB_LASER_UNIT_B_IP:-192.168.9.102}"
DURATION="${LASER_LIVE_PREVIEW_DURATION_SEC:-55}"
INTERVAL="${LASER_LIVE_PREVIEW_SAMPLE_INTERVAL_SEC:-5}"
REQUIRE_COMPLETED="${LASER_LIVE_PREVIEW_REQUIRE_COMPLETED:-0}"
REQUIRE_FUSED="${LASER_LIVE_PREVIEW_REQUIRE_FUSED:-0}"
POST_COMPLETION_SEC="${LASER_LIVE_PREVIEW_POST_COMPLETION_SEC:-45}"
PREVIEW_BUDGET="${LASER_LIVE_PREVIEW_POINT_BUDGET:-131072}"
ADB_CMD=("$ADB_BIN" -s "$SERIAL")

mkdir -p "$OUT" "$OUT/ui" "$OUT/meminfo" "$OUT/proc" "$OUT/server"
: > "$OUT/samples.jsonl"
: > "$OUT/run.log"
: > "$OUT/logcat.txt"

TOKEN=""
SCAN_ID=""
BASELINE_ID=0
LOGCAT_PID=""
LASERWORKER_LOG="$(readlink -f /proc/$(ss -ltnp 2>/dev/null | sed -n 's/.*:18087.*pid=\([0-9]*\).*/\1/p' | head -1)/fd/1 2>/dev/null || true)"
LASERWORKER_OFFSET=0
CLEANUP_MODE="none"
CLEANUP_OK=false
RUN_ERROR=""
SIGNAL=""

log() {
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"
}

json_field() {
    local path="$1"
    python3 -c '
import json, sys
cur = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    if not key:
        continue
    if not isinstance(cur, dict) or key not in cur:
        raise SystemExit(1)
    cur = cur[key]
if isinstance(cur, bool):
    print(str(cur).lower())
elif cur is not None:
    print(cur)
' "$path"
}

api_get() {
    curl -fsS --max-time 5 "$1" -H "Authorization: Bearer $TOKEN"
}

active_url() {
    printf '%s/v1/scans/laser/active?unit_a_ip=%s&unit_b_ip=%s' "$GATEWAY" "$UNIT_A_IP" "$UNIT_B_IP"
}

dump_ui() {
    local target="$1"
    local attempt
    for attempt in $(seq 1 10); do
        "${ADB_CMD[@]}" shell rm -f /sdcard/gomob-harness-ui.xml >/dev/null 2>&1 || true
        if "${ADB_CMD[@]}" shell uiautomator dump --compressed /sdcard/gomob-harness-ui.xml \
            >> "$OUT/ui/dump-errors.log" 2>&1 &&
            "${ADB_CMD[@]}" shell cat /sdcard/gomob-harness-ui.xml > "$target" 2>/dev/null &&
            [[ -s "$target" ]]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

ui_has() {
    local file="$1" text="$2"
    python3 - "$file" "$text" <<'PY'
import sys
from xml.etree import ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
needle = sys.argv[2]
for node in root.iter("node"):
    if needle in node.attrib.get("text", "") or needle in node.attrib.get("content-desc", ""):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

tap_text() {
    local text="$1" mode="${2:-exact}" ui="$OUT/ui/tap-$(date +%s%N).xml" point
    dump_ui "$ui" || return 1
    point="$(python3 - "$ui" "$text" "$mode" <<'PY'
import re, sys
from xml.etree import ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
needle, mode = sys.argv[2], sys.argv[3]
parents = {child: parent for parent in root.iter() for child in parent}

def matches(node):
    values = (node.attrib.get("text", ""), node.attrib.get("content-desc", ""))
    return any((v == needle if mode == "exact" else needle in v) for v in values)

for node in root.iter("node"):
    if not matches(node):
        continue
    target = node
    while target is not None and target.attrib.get("clickable") != "true":
        target = parents.get(target)
    target = target or node
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", target.attrib.get("bounds", ""))
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)"
    [[ -n "$point" ]] || return 1
    # shellcheck disable=SC2086
    "${ADB_CMD[@]}" shell input tap $point >/dev/null
}

wait_ui() {
    local text="$1" timeout="${2:-20}" start="$(date +%s)" ui
    while (( $(date +%s) - start < timeout )); do
        ui="$OUT/ui/wait-$(date +%s%N).xml"
        if dump_ui "$ui" && ui_has "$ui" "$text"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

db_scalar() {
    podman exec gomob-pg psql -U gomob -d gomob -At -c "$1" 2>/dev/null | tail -1
}

write_meta() {
    local heap_limit
    heap_limit="$("${ADB_CMD[@]}" shell getprop dalvik.vm.heapgrowthlimit 2>/dev/null | tr -d '\r')"
    python3 - "$OUT/meta.json" "$SERIAL" "$DURATION" "$INTERVAL" "$REQUIRE_COMPLETED" "$REQUIRE_FUSED" \
        "$POST_COMPLETION_SEC" "$PREVIEW_BUDGET" "$BASELINE_ID" "$SCAN_ID" "$heap_limit" <<'PY'
import json, sys
path, serial, duration, interval, require_completed, require_fused, post_completion_sec, budget, baseline, scan_id, heap_limit = sys.argv[1:]
with open(path, "w", encoding="utf-8") as f:
    json.dump({
        "serial": serial,
        "duration_sec": int(duration),
        "sample_interval_sec": int(interval),
        "require_completed": require_completed == "1",
        "require_fused": require_fused == "1",
        "post_completion_sec": int(post_completion_sec),
        "preview_budget_per_unit": int(budget),
        "baseline_scan_id": int(baseline),
        "scan_id": int(scan_id) if scan_id else None,
        "heap_growth_limit": heap_limit,
    }, f, ensure_ascii=False, indent=2)
PY
}

collect_sample() {
    local seq="$1" elapsed="$2" phase_hint="${3:-running}" pid ui mem proc server
    pid="$("${ADB_CMD[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
    ui="$OUT/ui/sample-$(printf '%03d' "$seq").xml"
    mem="$OUT/meminfo/sample-$(printf '%03d' "$seq").txt"
    proc="$OUT/proc/sample-$(printf '%03d' "$seq").txt"
    server="$OUT/server/sample-$(printf '%03d' "$seq").json"
    dump_ui "$ui" || : > "$ui"
    if [[ -n "$pid" ]]; then
        "${ADB_CMD[@]}" shell dumpsys meminfo "$pid" > "$mem" 2>&1 || true
        "${ADB_CMD[@]}" shell cat "/proc/$pid/status" > "$proc" 2>&1 || true
    else
        : > "$mem"; : > "$proc"
    fi
    api_get "$(active_url)" > "$server" 2>/dev/null || printf '{"api_error":true}\n' > "$server"

    python3 - "$seq" "$elapsed" "$phase_hint" "$pid" "$ui" "$mem" "$proc" "$server" <<'PY' >> "$OUT/samples.jsonl"
import json, re, sys, time
from pathlib import Path
from xml.etree import ElementTree as ET

seq, elapsed, phase_hint, pid, ui_path, mem_path, proc_path, server_path = sys.argv[1:]
mem = Path(mem_path).read_text(encoding="utf-8", errors="ignore")
proc = Path(proc_path).read_text(encoding="utf-8", errors="ignore")
try:
    server = json.loads(Path(server_path).read_text(encoding="utf-8"))
except Exception:
    server = {"api_error": True}

def table_row(name):
    m = re.search(rf"^\s*{re.escape(name)}\s+(.+)$", mem, re.M)
    if not m:
        return []
    return [int(x.replace(",", "")) for x in re.findall(r"\d[\d,]*", m.group(1))]

dalvik = table_row("Dalvik Heap")
total = table_row("TOTAL")
status = {}
for key in ("VmRSS", "VmHWM", "VmSwap"):
    m = re.search(rf"^{key}:\s+(\d+)\s+kB", proc, re.M)
    status[key.lower() + "_kb"] = int(m.group(1)) if m else None

texts = []
try:
    root = ET.parse(ui_path).getroot()
    for node in root.iter("node"):
        value = node.attrib.get("text", "").strip()
        if value:
            texts.append(value)
except Exception:
    pass

if phase_hint == "completed":
    phase = "completed"
elif server.get("status") == "capturing" or server.get("live_state") == "scanning":
    phase = "scanning"
elif server.get("status") == "fusing" or server.get("live_state") in ("fusing", "done"):
    phase = "fusing"
elif any("扫描完成" in text for text in texts):
    phase = "final_loading"
else:
    phase = "running"

print(json.dumps({
    "seq": int(seq),
    "elapsed_sec": int(elapsed),
    "wall_time": time.time(),
    "phase": phase,
    "pid": int(pid) if pid else None,
    "dalvik_heap_size_kb": dalvik[5] if len(dalvik) >= 8 else None,
    "dalvik_heap_alloc_kb": dalvik[6] if len(dalvik) >= 8 else None,
    "dalvik_heap_free_kb": dalvik[7] if len(dalvik) >= 8 else None,
    "total_pss_kb": total[0] if total else None,
    "total_rss_kb": total[4] if len(total) >= 5 else None,
    **status,
    "ui_texts": texts,
    "server": server,
}, ensure_ascii=False))
PY
}

poll_terminal() {
    local deadline=$(( $(date +%s) + 30 )) active status
    while (( $(date +%s) < deadline )); do
        active="$(api_get "$(active_url)" 2>/dev/null || true)"
        if [[ -n "$active" ]] && [[ "$(printf '%s' "$active" | json_field active 2>/dev/null || true)" == "false" ]]; then
            if [[ -n "$SCAN_ID" ]]; then
                status="$(db_scalar "SELECT status FROM laser_scan_jobs WHERE id=$SCAN_ID;" || true)"
                case "$status" in
                    cancelled|done|failed) return 0 ;;
                esac
            else
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

handle_signal() {
    SIGNAL="$1"
    RUN_ERROR="采样被信号 $1 中断"
    case "$1" in
        INT) exit 130 ;;
        TERM) exit 143 ;;
        *) exit 1 ;;
    esac
}

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM
    if [[ -n "$SCAN_ID" ]]; then
        local active
        active="$(api_get "$(active_url)" 2>/dev/null || true)"
        if [[ "$(printf '%s' "$active" | json_field active 2>/dev/null || true)" == "true" ]]; then
            if tap_text "取消扫描" exact; then
                CLEANUP_MODE="ui"
                log "已从 UI 请求取消 scan_id=$SCAN_ID"
            else
                CLEANUP_MODE="ui_back"
                log "取消按钮当前不可用，使用系统返回键触发页面安全停止 scan_id=$SCAN_ID"
                "${ADB_CMD[@]}" shell input keyevent 4 >/dev/null 2>&1 || true
            fi
        else
            CLEANUP_MODE="already_terminal"
        fi
        if poll_terminal; then
            CLEANUP_OK=true
        elif [[ "$CLEANUP_MODE" == "ui" || "$CLEANUP_MODE" == "ui_back" ]]; then
            CLEANUP_MODE="rest_fallback"
            log "UI 停止未确认终态，使用 REST 兜底 scan_id=$SCAN_ID"
            curl -fsS --max-time 10 -X POST "$GATEWAY/v1/scans/laser/$SCAN_ID/stop" \
                -H "Authorization: Bearer $TOKEN" > "$OUT/rest-stop.json" 2>&1 || true
            if poll_terminal; then CLEANUP_OK=true; fi
        fi
    else
        CLEANUP_MODE="no_owned_scan"
        CLEANUP_OK=true
    fi
    if [[ -n "$LOGCAT_PID" ]]; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
    if [[ -n "$LASERWORKER_LOG" && -f "$LASERWORKER_LOG" ]]; then
        tail -c "+$((LASERWORKER_OFFSET + 1))" "$LASERWORKER_LOG" > "$OUT/laserworker.log" 2>/dev/null || true
    fi
    python3 - "$OUT/cleanup.json" "$CLEANUP_MODE" "$CLEANUP_OK" "$SCAN_ID" "$RUN_ERROR" "$exit_code" "$SIGNAL" <<'PY'
import json, sys
path, mode, ok, scan_id, run_error, exit_code, signal = sys.argv[1:]
with open(path, "w", encoding="utf-8") as f:
    json.dump({
        "mode": mode,
        "ok": ok == "true",
        "scan_id": int(scan_id) if scan_id else None,
        "run_error": run_error or None,
        "sampler_exit_code": int(exit_code),
        "signal": signal or None,
    }, f, ensure_ascii=False, indent=2)
PY
    write_meta
    log "采样结束，清理 mode=$CLEANUP_MODE ok=$CLEANUP_OK"
    # 采样错误写入 cleanup.json，由 analyze.py 统一给 PASS/WARN/FAIL；保证 dev.sh 不会跳过分析器。
    exit 0
}
trap cleanup EXIT
trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM

require_positive_int() {
    local name="$1" value="$2"
    [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
        RUN_ERROR="$name 必须是正整数，当前=$value"
        return 1
    }
}

main() {
    log "前置检查 serial=$SERIAL duration=${DURATION}s"
    require_positive_int LASER_LIVE_PREVIEW_DURATION_SEC "$DURATION" || return 2
    require_positive_int LASER_LIVE_PREVIEW_SAMPLE_INTERVAL_SEC "$INTERVAL" || return 2
    require_positive_int LASER_LIVE_PREVIEW_POST_COMPLETION_SEC "$POST_COMPLETION_SEC" || return 2
    require_positive_int LASER_LIVE_PREVIEW_POINT_BUDGET "$PREVIEW_BUDGET" || return 2
    "${ADB_CMD[@]}" get-state >/dev/null 2>&1 || { RUN_ERROR="adb 设备不在线"; return 2; }
    curl -fsS --max-time 3 "$GATEWAY/healthz" >/dev/null || { RUN_ERROR="devserver 不可用"; return 2; }
    TOKEN="$(curl -fsS --max-time 5 -X POST "$GATEWAY/v1/auth/login" \
        -H 'Content-Type: application/json' --data '{"username":"shenhm","password":"shenhm123"}' | json_field data.access_token)"
    [[ -n "$TOKEN" ]] || { RUN_ERROR="无法取得 harness token"; return 2; }

    local unit active active_jobs
    for unit in a b; do
        local status
        status="$(api_get "$GATEWAY/v1/scans/laser/device-status?unit=$unit")"
        [[ "$(printf '%s' "$status" | json_field online 2>/dev/null || true)" == "true" ]] || {
            RUN_ERROR="激光单元 $unit 不在线"; return 2;
        }
        [[ "$(printf '%s' "$status" | json_field state 2>/dev/null || true)" == "READY" ]] || {
            RUN_ERROR="激光单元 $unit 未 READY"; return 2;
        }
    done
    active="$(api_get "$(active_url)")"
    [[ "$(printf '%s' "$active" | json_field active)" == "false" ]] || { RUN_ERROR="已有活动扫描，拒绝接管"; return 2; }
    active_jobs="$(db_scalar "SELECT count(*) FROM laser_scan_jobs WHERE status IN ('capturing','fusing');")"
    [[ "$active_jobs" == "0" ]] || { RUN_ERROR="数据库已有进行中扫描，拒绝接管"; return 2; }
    BASELINE_ID="$(db_scalar 'SELECT COALESCE(max(id),0) FROM laser_scan_jobs;')"

    export ADB_DEVICE="$SERIAL"
    (cd "$ROOT" && ./dev.sh install) >> "$OUT/run.log" 2>&1 || { RUN_ERROR="APK 安装失败"; return 3; }
    (cd "$ROOT" && ./dev.sh reverse) >> "$OUT/run.log" 2>&1 || true
    if [[ -n "$LASERWORKER_LOG" && -f "$LASERWORKER_LOG" ]]; then
        LASERWORKER_OFFSET="$(stat -c %s "$LASERWORKER_LOG" 2>/dev/null || echo 0)"
    fi
    "${ADB_CMD[@]}" logcat -T 1 -v threadtime > "$OUT/logcat.txt" 2>&1 &
    LOGCAT_PID=$!
    "${ADB_CMD[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    "${ADB_CMD[@]}" shell am start -n "$ACTIVITY" >/dev/null
    sleep 8

    local ui="$OUT/ui/launch.xml"
    dump_ui "$ui" || { RUN_ERROR="无法读取启动 UI"; return 4; }
    if ui_has "$ui" "登 录"; then
        tap_text "登 录" exact || { RUN_ERROR="找不到登录按钮"; return 4; }
        wait_ui "首页" 20 || { RUN_ERROR="登录后未进入主界面"; return 4; }
    fi
    tap_text "3D" exact || { RUN_ERROR="找不到 3D tab"; return 4; }
    wait_ui "车辆外廓扫描" 15 || { RUN_ERROR="3D 页未出现车辆外廓入口"; return 4; }
    tap_text "车辆外廓扫描" exact || { RUN_ERROR="无法进入车辆外廓扫描"; return 4; }
    wait_ui "开始扫描" 20 || { RUN_ERROR="激光页未出现开始扫描"; return 4; }
    tap_text "开始扫描" exact || { RUN_ERROR="无法点击开始扫描"; return 4; }

    local deadline=$(( $(date +%s) + 20 )) body candidate
    while (( $(date +%s) < deadline )); do
        body="$(api_get "$(active_url)" 2>/dev/null || true)"
        if [[ "$(printf '%s' "$body" | json_field active 2>/dev/null || true)" == "true" ]]; then
            candidate="$(printf '%s' "$body" | json_field scan_id 2>/dev/null || true)"
            if [[ "$candidate" =~ ^[0-9]+$ ]] && (( candidate > BASELINE_ID )); then
                SCAN_ID="$candidate"
                break
            fi
        fi
        sleep 1
    done
    [[ -n "$SCAN_ID" ]] || { RUN_ERROR="UI 起扫后未确认新 scan_id"; return 4; }
    write_meta
    log "已确认 scan_id=$SCAN_ID，开始联合采样"

    local start now elapsed seq=0 completed=false
    start="$(date +%s)"
    while true; do
        now="$(date +%s)"; elapsed=$((now - start))
        collect_sample "$seq" "$elapsed" running
        seq=$((seq + 1))
        local status
        status="$(db_scalar "SELECT status FROM laser_scan_jobs WHERE id=$SCAN_ID;" || true)"
        if [[ "$status" == "done" ]]; then
            if [[ "$REQUIRE_COMPLETED" == "1" ]]; then
                if wait_ui "重新扫描" 60; then
                    completed=true
                    now="$(date +%s)"; elapsed=$((now - start))
                    collect_sample "$seq" "$elapsed" completed
                    seq=$((seq + 1))
                    local completion_deadline=$(( $(date +%s) + POST_COMPLETION_SEC ))
                    while (( $(date +%s) < completion_deadline )); do
                        local remaining=$((completion_deadline - $(date +%s)))
                        local wait_sec="$INTERVAL"
                        (( wait_sec > remaining )) && wait_sec="$remaining"
                        (( wait_sec > 0 )) && sleep "$wait_sec"
                        now="$(date +%s)"; elapsed=$((now - start))
                        collect_sample "$seq" "$elapsed" completed
                        seq=$((seq + 1))
                    done
                    break
                fi
                RUN_ERROR="服务端 done 后 App 未进入完成态"
                return 5
            fi
            completed=true
        fi
        (( elapsed >= DURATION )) && break
        sleep "$INTERVAL"
    done
    if [[ "$REQUIRE_COMPLETED" == "1" && "$completed" != "true" ]]; then
        RUN_ERROR="soak 时限内扫描未完成"
        return 5
    fi
    return 0
}

main "$@"
