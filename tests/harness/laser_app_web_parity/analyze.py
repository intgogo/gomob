#!/usr/bin/env python3
"""解析 ClientParity 的 PARITY_JSON，判定网页/App 配置和全量结果是否同源一致。"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any


PREFIX = "PARITY_JSON:"
REVISION_KEYS = ("site_revision", "region_revision", "background_revision")
CLIENT_CONTRACTS = ("web", "app", "measured_pcd")


def read_records(path: Path) -> dict[str, dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}

    def add(client: str, record: dict[str, Any]) -> None:
        if client in records:
            raise ValueError(f"{client} 出现重复 PARITY_JSON")
        records[client] = record

    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if PREFIX not in line:
            continue
        raw = line.split(PREFIX, 1)[1].strip()
        payload = json.loads(raw)
        if "web" in payload and "app" in payload:
            if not isinstance(payload["web"], dict) or not isinstance(payload["app"], dict):
                raise ValueError("web/app PARITY_JSON 必须是对象")
            add("web", payload["web"])
            add("app", payload["app"])
            continue
        client = str(payload.get("client", "")).lower()
        if client in ("web", "app"):
            add(client, payload)
    return records


def revision_value(record: dict[str, Any], key: str) -> Any:
    effective = record.get("effective")
    if isinstance(effective, dict) and key in effective:
        return effective[key]
    return record.get(key)


def nonempty(value: Any) -> bool:
    if isinstance(value, bool) or value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (int, float)):
        return math.isfinite(float(value)) and float(value) > 0
    return False


def result_of(record: dict[str, Any]) -> dict[str, Any]:
    result = record.get("result")
    return result if isinstance(result, dict) else {}


def has_path(result: dict[str, Any], path: tuple[str, ...]) -> bool:
    current: Any = result
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return False
        current = current[key]
    return True


def validate_result_shape(result: dict[str, Any]) -> list[str]:
    missing: list[str] = []
    groups = (
        ("会话", (("session_key",),)),
        ("融合点云键", (("result_object_key",),)),
        ("A 点云键", (("unit_a_object_key",),)),
        ("B 点云键", (("unit_b_object_key",),)),
        ("车辆测量点云键", (("measured_object_key",),)),
        ("融合点数", (("points",),)),
        ("A 点数", (("pts_a",),)),
        ("B 点数", (("pts_b",),)),
        ("对齐方式", (("align_method",),)),
        ("site revision", (("site_revision",),)),
        ("region revision", (("region_revision",),)),
        ("测量模式", (("measure_mode",), ("meas_mode",))),
        ("测量有效性", (("measure_valid",),)),
        ("车长", (("length_mm",), ("dimensions", "length_mm"), ("dimensions", "length"))),
        ("车宽", (("width_mm",), ("dimensions", "width_mm"), ("dimensions", "width"))),
        ("车高", (("height_mm",), ("dimensions", "height_mm"), ("dimensions", "height"))),
        ("合规", (("compliant",), ("compliance",))),
        ("背景存在", (("background_set",),)),
        ("背景兼容", (("background_compatible",),)),
        ("背景原因", (("background_reason",),)),
        ("背景 revision", (("background_revision_id",),)),
        ("背景 schema", (("background_schema",),)),
        ("前景点数", (("fg_points",), ("foreground_points",))),
        ("实际测量点数", (("measured_points",),)),
        ("轴信息", (("axle",), ("num_axles",), ("axle_valid",))),
        ("货箱信息", (("cargo_box",), ("has_cargo_box",))),
        ("叠加几何", (("overlay",),)),
        ("地面 nx", (("ground_nx",),)),
        ("地面 ny", (("ground_ny",),)),
        ("地面 nz", (("ground_nz",),)),
        ("地面 d", (("ground_d",),)),
        ("地面有效性", (("ground_valid",),)),
    )
    for label, paths in groups:
        if not any(has_path(result, path) for path in paths):
            missing.append(label)
    return missing


def compare(left: Any, right: Any, path: str = "result") -> list[str]:
    diffs: list[str] = []
    if isinstance(left, bool) or isinstance(right, bool):
        if type(left) is not type(right) or left != right:
            diffs.append(f"{path}: web={left!r} app={right!r}")
        return diffs
    if isinstance(left, dict) and isinstance(right, dict):
        for key in sorted(set(left) | set(right)):
            child = f"{path}.{key}"
            if key not in left:
                diffs.append(f"{child}: web 缺字段")
            elif key not in right:
                diffs.append(f"{child}: app 缺字段")
            else:
                diffs.extend(compare(left[key], right[key], child))
        return diffs
    if isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return [f"{path}: 长度 web={len(left)} app={len(right)}"]
        for index, (lv, rv) in enumerate(zip(left, right)):
            diffs.extend(compare(lv, rv, f"{path}[{index}]"))
        return diffs
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        if not (math.isfinite(float(left)) and math.isfinite(float(right))):
            if left != right:
                diffs.append(f"{path}: web={left!r} app={right!r}")
        elif float(left) != float(right):
            diffs.append(f"{path}: web={left!r} app={right!r}")
        return diffs
    if left != right:
        diffs.append(f"{path}: web={left!r} app={right!r}")
    return diffs


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".dev/laser_app_web_parity/parity.log")
    if path.is_dir():
        path = path / "parity.log"
    if not path.is_file():
        print(f"异常：未找到 Go 测试日志 {path}")
        return 1

    try:
        records = read_records(path)
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        print(f"异常：PARITY_JSON 解析失败：{exc}")
        return 1

    if set(records) != {"web", "app"}:
        print(f"异常：需要 web/app 两份 PARITY_JSON，实际得到 {sorted(records)}")
        return 1

    web, app = records["web"], records["app"]
    errors: list[str] = []

    raw_text = path.read_text(encoding="utf-8", errors="replace")
    missing_contracts = [
        name for name in CLIENT_CONTRACTS
        if f"CLIENT_CONTRACT:{name}:PASS" not in raw_text
    ]
    if missing_contracts:
        errors.append("缺少真实客户端/PCD 契约：" + "、".join(missing_contracts))
    else:
        print("真实契约：Web 状态机、App DTO/repository、measured PCD 来源均已执行")

    print("配置 revision：")
    for key in REVISION_KEYS:
        wv, av = revision_value(web, key), revision_value(app, key)
        state = "正常" if nonempty(wv) and wv == av else "异常"
        print(f"  {key}: web={wv!r} app={av!r} → {state}")
        if not nonempty(wv) or not nonempty(av):
            errors.append(f"{key} 缺失")
        elif wv != av:
            errors.append(f"{key} 不一致")

    web_result, app_result = result_of(web), result_of(app)
    for client, result in (("web", web_result), ("app", app_result)):
        missing = validate_result_shape(result)
        if missing:
            errors.append(f"{client} result 缺少：{'、'.join(missing)}")

    diffs = compare(web_result, app_result)
    if diffs:
        errors.append(f"全量结果存在 {len(diffs)} 处差异")
        print("全量结果差异：")
        for item in diffs[:30]:
            print(f"  - {item}")
        if len(diffs) > 30:
            print(f"  - 其余 {len(diffs) - 30} 处省略")
    else:
        print("全量结果：网页/App canonical JSON 完全一致")

    if errors:
        print("结论：异常 — " + "；".join(errors))
        return 1
    print("结论：正常 — site/region/background revision 同源，点云、外廓、轴、货箱、overlay 和 ground 全量一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
