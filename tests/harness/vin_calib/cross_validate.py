#!/usr/bin/env python3
"""HLSD8↔L' 标定整姿态留出交叉验证。"""

from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path

import cv2
import numpy as np

import calibrate as base


FLAGS = cv2.CALIB_ZERO_TANGENT_DIST | cv2.CALIB_FIX_K2 | cv2.CALIB_FIX_K3
FOLDS = 5
# 终态精度门：仅用于已完成 PTS/SCR 映射或同步光学事件校正的数据。
# 当前无硬触发 5fps 日常采集的 native 回调最近邻门是 100ms，不能冒充曝光级同步。
MAX_SYNC_DELTA_US = 25_000
MAX_CROSS_CAMERA_RMS_PX = 2.0
MAX_ROTATION_RANGE_DEG = 0.5
MAX_PRINCIPAL_POINT_RANGE_PX = 12.0
MAX_TRANSLATION_RANGE_MM = 3.0


def check_sync(cap_dirs):
    missing = []
    over = []
    for cap_dir in cap_dirs:
        try:
            meta = json.loads((cap_dir / "meta.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            missing.append(cap_dir.name)
            continue
        sync = meta.get("sync") or {}
        deltas = [sync.get("hlsd8LprimeDeltaUs"), sync.get("lprimeDepthDeltaUs")]
        if any(value is None for value in deltas):
            missing.append(cap_dir.name)
        elif any(int(value) > MAX_SYNC_DELTA_US for value in deltas):
            over.append({"capture": cap_dir.name, "deltas_us": deltas})
    return missing, over


def collect(calib_dir):
    board = base._board()
    detector = base._make_detector(board)
    all_obj = board.getChessboardCorners()
    records = []
    h_size = l_size = None
    for cap_dir in sorted(calib_dir.glob("calib_*")):
        corners_h, ids_h, shape_h = base._detect(detector, str(cap_dir / "rgb1300.jpg"))
        corners_l, ids_l, shape_l = base._detect(detector, str(cap_dir / "lprime.jpg"))
        if shape_h:
            h_size = (shape_h[1], shape_h[0])
        if shape_l:
            l_size = (shape_l[1], shape_l[0])
        record = {"name": cap_dir.name, "h": None, "l": None, "stereo": None}
        nh = 0 if ids_h is None else len(ids_h)
        nl = 0 if ids_l is None else len(ids_l)
        if nh >= base.MIN_CORNERS:
            record["h"] = board.matchImagePoints(corners_h, ids_h)
        if nl >= base.MIN_CORNERS:
            record["l"] = board.matchImagePoints(corners_l, ids_l)
        if nh >= base.MIN_CORNERS and nl >= base.MIN_CORNERS:
            flat_h = ids_h.flatten()
            flat_l = ids_l.flatten()
            common = np.intersect1d(flat_h, flat_l)
            if len(common) >= base.MIN_CORNERS:
                map_h = {int(value): corners_h[index, 0] for index, value in enumerate(flat_h)}
                map_l = {int(value): corners_l[index, 0] for index, value in enumerate(flat_l)}
                obj = np.array([all_obj[value] for value in common], np.float32).reshape(-1, 1, 3)
                img_h = np.array([map_h[value] for value in common], np.float32).reshape(-1, 1, 2)
                img_l = np.array([map_l[value] for value in common], np.float32).reshape(-1, 1, 2)
                record["stereo"] = (obj, img_h, img_l)
        records.append(record)
    return {"records": records, "h_size": h_size, "l_size": l_size}


def fit(data, held):
    h_obj, h_img, l_obj, l_img, s_obj, s_h, s_l = [], [], [], [], [], [], []
    for record in data["records"]:
        if record["name"] in held:
            continue
        if record["h"] is not None:
            obj, img = record["h"]
            h_obj.append(obj)
            h_img.append(img)
        if record["l"] is not None:
            obj, img = record["l"]
            l_obj.append(obj)
            l_img.append(img)
        if record["stereo"] is not None:
            obj, img_h, img_l = record["stereo"]
            s_obj.append(obj)
            s_h.append(img_h)
            s_l.append(img_l)
    _, kh, dh, _, _ = cv2.calibrateCamera(h_obj, h_img, data["h_size"], None, None, flags=FLAGS)
    _, kl, dl, _, _ = cv2.calibrateCamera(l_obj, l_img, data["l_size"], None, None, flags=FLAGS)
    _, _, _, _, _, rotation, translation, _, _ = cv2.stereoCalibrate(
        s_obj,
        s_l,
        s_h,
        kl,
        dl,
        kh,
        dh,
        data["l_size"],
        flags=cv2.CALIB_FIX_INTRINSIC,
        criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_MAX_ITER, 200, 1e-6),
    )
    return {"kh": kh, "dh": dh, "kl": kl, "dl": dl, "r": rotation, "t": translation.reshape(3)}


def squared_errors(observed, projected):
    delta = projected.reshape(-1, 2) - observed.reshape(-1, 2)
    return np.sum(delta * delta, axis=1)


def cross_errors(record, model):
    obj, image_h, image_l = record["stereo"]
    points = obj.reshape(-1, 3).T
    ok_l, rvec_l, tvec_l = cv2.solvePnP(obj, image_l, model["kl"], model["dl"])
    ok_h, rvec_h, tvec_h = cv2.solvePnP(obj, image_h, model["kh"], model["dh"])
    if not ok_l or not ok_h:
        return np.array([]), np.array([])
    rot_l, _ = cv2.Rodrigues(rvec_l)
    in_l = rot_l @ points + tvec_l
    in_h = model["r"] @ in_l + model["t"].reshape(3, 1)
    projected_h, _ = cv2.projectPoints(in_h.T.reshape(-1, 1, 3), np.zeros(3), np.zeros(3), model["kh"], model["dh"])

    rot_h, _ = cv2.Rodrigues(rvec_h)
    in_h2 = rot_h @ points + tvec_h
    in_l2 = model["r"].T @ (in_h2 - model["t"].reshape(3, 1))
    projected_l, _ = cv2.projectPoints(in_l2.T.reshape(-1, 1, 3), np.zeros(3), np.zeros(3), model["kl"], model["dl"])
    return squared_errors(image_h, projected_h), squared_errors(image_l, projected_l)


def rms(arrays):
    valid = [array for array in arrays if array.size]
    return float(math.sqrt(np.concatenate(valid).mean())) if valid else 999.0


def main():
    cv2.setNumThreads(1)
    calib_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_calib_caps")
    out_dir = Path(os.environ.get("OUTPUT_DIR", ".dev/vin_calib"))
    out_dir.mkdir(parents=True, exist_ok=True)
    cap_dirs = sorted(path for path in calib_dir.glob("calib_*") if path.is_dir())
    missing_sync, over_sync = check_sync(cap_dirs)
    allow_legacy = os.environ.get("VIN_CALIB_ALLOW_LEGACY_SYNC") == "1"
    if (missing_sync or over_sync) and not allow_legacy:
        report = {
            "verdict": "异常",
            "reason": "标定采集未通过三路时间同步门",
            "missing_sync": missing_sync,
            "over_sync": over_sync,
        }
        (out_dir / "cross_validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"结论：✗ 异常 — {report['reason']}（缺失 {len(missing_sync)}，超窗 {len(over_sync)}）")
        return 2

    data = collect(calib_dir)
    names = sorted(record["name"] for record in data["records"] if record["stereo"] is not None)
    if len(names) < 20:
        print(f"结论：✗ 异常 — 可用双目共见姿态 {len(names)} < 20")
        return 2
    folds = [names[index::FOLDS] for index in range(FOLDS)]
    fold_reports = []
    params = []
    for index, held_names in enumerate(folds):
        held = set(held_names)
        model = fit(data, held)
        errors_lh, errors_hl = [], []
        for record in data["records"]:
            if record["name"] in held and record["stereo"] is not None:
                one, two = cross_errors(record, model)
                errors_lh.append(one)
                errors_hl.append(two)
        rvec, _ = cv2.Rodrigues(model["r"])
        fold_reports.append(
            {
                "fold": index,
                "held": held_names,
                "lprime_to_hlsd8_rms_px": rms(errors_lh),
                "hlsd8_to_lprime_rms_px": rms(errors_hl),
            }
        )
        params.append(
            [
                model["kh"][0, 2], model["kh"][1, 2], model["kl"][0, 2], model["kl"][1, 2],
                float(np.linalg.norm(rvec) * 180.0 / math.pi),
                *(model["t"] * 1000.0),
            ]
        )
    params = np.asarray(params, np.float64)
    l_to_h = [fold["lprime_to_hlsd8_rms_px"] for fold in fold_reports]
    h_to_l = [fold["hlsd8_to_lprime_rms_px"] for fold in fold_reports]
    stability = {
        "h_cx_range_px": float(np.ptp(params[:, 0])),
        "h_cy_range_px": float(np.ptp(params[:, 1])),
        "l_cx_range_px": float(np.ptp(params[:, 2])),
        "l_cy_range_px": float(np.ptp(params[:, 3])),
        "rotation_range_deg": float(np.ptp(params[:, 4])),
        "translation_range_mm": [float(np.ptp(params[:, index])) for index in range(5, 8)],
    }
    failures = []
    if max(l_to_h + h_to_l) > MAX_CROSS_CAMERA_RMS_PX:
        failures.append("留出跨相机 RMS 超 2px")
    if stability["rotation_range_deg"] > MAX_ROTATION_RANGE_DEG:
        failures.append("外参旋转跨折波动超 0.5°")
    if max(stability[key] for key in ("h_cx_range_px", "h_cy_range_px", "l_cx_range_px", "l_cy_range_px")) > MAX_PRINCIPAL_POINT_RANGE_PX:
        failures.append("主点跨折波动超 12px")
    if max(stability["translation_range_mm"]) > MAX_TRANSLATION_RANGE_MM:
        failures.append("外参平移跨折波动超 3mm")
    warnings = []
    if missing_sync or over_sync:
        warnings.append("使用了旧的无同步元数据标定集")
    verdict = "异常" if failures else ("警告" if warnings else "正常")
    report = {
        "verdict": verdict,
        "distortion_model": "brown_k1_only",
        "pose_count": len(names),
        "folds": fold_reports,
        "aggregate": {
            "lprime_to_hlsd8_mean_rms_px": float(np.mean(l_to_h)),
            "hlsd8_to_lprime_mean_rms_px": float(np.mean(h_to_l)),
            "worst_rms_px": float(max(l_to_h + h_to_l)),
        },
        "stability": stability,
        "failures": failures,
        "warnings": warnings,
    }
    (out_dir / "cross_validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    details = failures or warnings or ["留出误差与参数稳定性均达标"]
    print(f"结论：{verdict} — " + "；".join(details))
    return 2 if failures else (1 if warnings else 0)


if __name__ == "__main__":
    raise SystemExit(main())
