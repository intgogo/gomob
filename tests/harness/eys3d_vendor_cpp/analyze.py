#!/usr/bin/env python3
# eys3d_vendor_cpp analyze — 从 logcat 采样出【可判定结论】:正常/警告/异常 + 原因。
#
# 解析 native VLOG(tag eys3d_vcpp / eys3d_stream)+ Kotlin(Eys3dCameraService)marker:
#   起流链: "vendor C++ 直驱会话"(零Java) / "FrameGrabber 校验通过"(cb==livePlyCallback) / "UVCCamera::connect rc=N"
#   帧:     "ourCb #N depth WxH color WxH serial=S centerDispX8=D valid=R% max=X meanNZ=Y" / "tick cbFrames=N" / "首帧深度到达"
#   零JNI 反向: 不应再出现 "ApcCamera" / "IFrameCallback" / "全 Java"(已退役)
#
# 致命(异常):无起流链 marker / connect rc!=0 / ourCb 零帧 / 出现 Java 帧路径 marker / VINSHIM。
# 质量(警告):fps 偏低 / valid 稀疏 / centerDispX8 越出 11bit 域 —— 起流与零JNI 通过,深度质量待优化。
import sys
import json
import re
import statistics
from pathlib import Path


PASS = 0
WARN = 1
FAIL = 2


def timed_counters(text, marker):
    rows = []
    pattern = re.compile(
        rf"(\d{{2}})-(\d{{2}}) (\d{{2}}):(\d{{2}}):(\d{{2}})\.(\d{{3}}).*{marker}(\d+)"
    )
    for month, day, hour, minute, second, millis, counter in pattern.findall(text):
        timestamp = (
            (((int(month) * 31 + int(day)) * 24 + int(hour)) * 60 + int(minute)) * 60
            + int(second)
            + int(millis) / 1000.0
        )
        rows.append((timestamp, int(counter)))
    return rows


def counter_rate(rows):
    if len(rows) < 2:
        return 0.0
    first = rows[0]
    last = rows[-1]
    elapsed = last[0] - first[0]
    return (last[1] - first[1]) / elapsed if elapsed > 0 and last[1] >= first[1] else 0.0


def analyze_output(out_dir):
    out = Path(out_dir)
    if not out.is_dir():
        print(f"FAIL: 输出目录不存在或不是目录: {out}")
        return FAIL

    sample_path = out / "sample.json"
    try:
        sample = json.loads(sample_path.read_text(encoding="utf-8"))
        secs = float(sample["duration_seconds"])
        log_name = str(sample.get("log_file", "logcat.txt"))
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
        print(f"FAIL: 采样元数据无效 {sample_path}: {error}")
        return FAIL
    if secs <= 0:
        print(f"FAIL: duration_seconds 必须大于 0，实际 {secs}")
        return FAIL
    if Path(log_name).name != log_name:
        print(f"FAIL: log_file 必须是输出目录内文件名，实际 {log_name!r}")
        return FAIL

    log = out / log_name
    try:
        text = log.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"FAIL: 读不到 logcat {log}: {error}")
        return FAIL

    # ① 起流链 marker
    zero_java = "vendor C++ 直驱会话" in text
    fg_ok = ("FrameGrabber 校验通过" in text) or ("cb==livePlyCallback" in text)
    m_conn = re.search(r"UVCCamera::connect rc=(-?\d+)", text)
    conn_rc = int(m_conn.group(1)) if m_conn else None

    # ② ourCb 深度帧
    rows = re.findall(
        r"ourCb #(\d+) depth (\d+)x(\d+) color (\d+)x(\d+) serial=(-?\d+) "
        r"centerDispX8=(\d+) valid=([\d.]+)% max=(\d+) meanNZ=(\d+)",
        text,
    )
    cb_max = 0
    valids, centers = [], []
    for r in rows:
        # 组序: 0=#N 1=dW 2=dH 3=cW 4=cH 5=serial 6=centerDispX8 7=valid 8=max 9=meanNZ
        cb_max = max(cb_max, int(r[0]))
        centers.append(int(r[6]))
        valids.append(float(r[7]))
    for t in re.findall(r"tick cbFrames=(\d+)", text):
        cb_max = max(cb_max, int(t))
    cb_timed = timed_counters(text, r"(?:tick cbFrames=|ourCb #)")
    fps = counter_rate(cb_timed)
    if fps <= 0 and cb_max > 0 and (zero_java or fg_ok):
        fps = cb_max / secs if secs > 0 else 0.0
    poll_first = "首帧深度到达" in text

    # ③ HLSD8 原厂全分辨率并发流：VINCreator native 固定 1..5fps + bandwidth 起流。
    hlsd8_old_10fps = "nego VINCreator 4160x832@10" in text
    hlsd8_vincreator_mode = "nego VINCreator 4160x832 fps=1..5 rc=0" in text
    hlsd8_timed = timed_counters(text, r"(?:tick color=|color\(MJPEG\) #)")
    hlsd8_fps = counter_rate(hlsd8_timed)
    hlsd8_has_frames = bool(hlsd8_timed)

    # ④ 零 JNI 反向 marker(退役后不应出现)
    java_markers = [m for m in ("ApcCamera", "IFrameCallback", "全 Java") if m in text]
    has_vinshim = "VINSHIM" in text

    print(f"起流链: 零Java会话={zero_java} FrameGrabber校验={fg_ok} connect_rc={conn_rc}")
    print(f"帧:     cbFrames(max)={cb_max} fps≈{fps:.1f} poll首帧={poll_first} ourCb采样={len(rows)}")
    print(
        f"HLSD8:  4160×832原厂协商={hlsd8_vincreator_mode} "
        f"持续帧={hlsd8_has_frames} fps≈{hlsd8_fps:.1f}"
    )
    if valids:
        print(f"质量:   valid中位={statistics.median(valids):.1f}% centerDispX8中位={statistics.median(centers)}")
    if java_markers:
        print(f"⚠ 检出 Java 帧路径 marker(退役后不应有): {java_markers}")
    if has_vinshim:
        print("⚠ 检出 VINSHIM：生产 libusb100.so 仍是诊断转发层")

    # 致命判定
    # ★ 关键判据:`eys3d_vcpp` 的 ourCb/tick 是【native vendor C++ 会话独有】日志(OnVendorFrame 仅经 FrameTrampoline
    #   到达;该 tag 全工程仅 eys3d_vendor_cpp_session.cpp 用)。其存在 = native 直驱在跑 + 帧路零 JNI(无 Java 接帧)。
    #   起流链 marker(零Java会话/FrameGrabber校验/connect rc)只在会话【启动】打一次;若相机已在流时采样(clear 后这些
    #   已滚出缓冲)→ 缺席不代表失败,以帧流为准。故致命只认:Java marker / connect 明确失败 / 完全无帧无 marker。
    has_frames = cb_max > 0 or len(rows) > 0
    mid_session = has_frames and not (zero_java and fg_ok)

    reasons = []
    if has_vinshim:
        reasons.append("检出 VINSHIM(必须换回 VINCreator 原厂 libusb100.so)")
    if hlsd8_old_10fps:
        reasons.append("HLSD8 仍以旧 10fps 协商，会挤死 RS-D550 mode25")
    if not hlsd8_has_frames:
        reasons.append("HLSD8 4160×832 并发流无持续帧")
    if java_markers:
        reasons.append(f"帧路径含 Java marker {java_markers}(应零 JNI)")
    if conn_rc is not None and conn_rc != 0:
        reasons.append(f"connect rc={conn_rc}(!=0,开流失败:fd/usbfs/参数顺序)")
    if not has_frames:
        if not (zero_java or fg_ok):
            reasons.append("无任何 native vendor 帧/起流 marker(设备离线/未进相机页/供电不足/起流失败)")
        else:
            reasons.append("起流 marker 在但 0 帧(连上未出流:供电/场景/USB)")

    if reasons:
        print("\nFAIL: " + "; ".join(reasons))
        print("   → 确认手机在深度相机页、带电 hub 供电、0x3438:0206 在线;Java marker 在=退役未净;connect!=0 查 fd/usbfs。")
        return FAIL

    if mid_session:
        print("ℹ 采样在会话中段:起流链 marker 已滚出缓冲,以 eys3d_vcpp 帧流为准"
              "(该 tag+ourCb=native 直驱+零 JNI 铁证)。")

    # 质量警告(非致命)
    warns = []
    if fps < 2.0:
        warns.append(f"fps≈{fps:.1f}<2(产帧偏慢/poll 漏)")
    if valids:
        vr = statistics.median(valids)
        cm = statistics.median(centers)
        if vr < 30.0:
            warns.append(f"valid 中位 {vr:.1f}%<30%(稀疏,待 densify)")
        if not (1 <= cm <= 2047):
            warns.append(f"centerDispX8 中位 {cm} 不在 mode25 11bit 域[1,2047]")
    if warns:
        print("\nWARN: " + "; ".join(warns) + " — 起流/零JNI 通过,深度质量待优化(densify/标定)。")
        return WARN

    vr = statistics.median(valids) if valids else 0.0
    cm = statistics.median(centers) if centers else 0
    print(f"\nPASS: native 零 Java 直驱出 mode25 真深度,fps≈{fps:.1f}、valid中位{vr:.1f}%、"
          f"centerDispX8={cm}、帧路径零 JNI。")
    return PASS


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    out_dir = args[0] if args else ".dev/eys3d_vendor_cpp"
    return analyze_output(out_dir)


if __name__ == "__main__":
    sys.exit(main())
