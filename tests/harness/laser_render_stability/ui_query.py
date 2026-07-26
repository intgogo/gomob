#!/usr/bin/env python3
"""解析车辆外廓页的 uiautomator XML，并提供稳定的点击与状态判定。"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Optional, Tuple
from xml.etree import ElementTree as ET


EXPECTED_STATUS = "扫描完成 · 融合源 2,050,753 点"
DIMENSION_PREFIX = "点云尺寸标注 "
MEASUREMENT_PREFIX = "车辆测量 "
WIREFRAME_PREFIX = "车辆外廓尺寸线框 "
EXPECTED_MEASUREMENTS = {
    "车辆测量 车长 1,768 mm",
    "车辆测量 车宽 531 mm",
    "车辆测量 车高 763 mm",
    "车辆测量 轴数 4 轴",
    "车辆测量 轴距 1 791 mm",
    "车辆测量 轴距 2 294 mm",
    "车辆测量 轴距 3 315 mm",
    "车辆测量 总轴距 1,400 mm",
    "车辆测量 前悬 231 mm",
    "车辆测量 后悬 170 mm",
    "车辆测量 货箱外长 1,060 mm",
    "车辆测量 货箱外宽 451 mm",
    "车辆测量 货箱深 519 mm",
}
DIMENSION_BADGE_RE = re.compile(r"^车(?:长|宽|高) [\d,]+$")
WIREFRAME_RE = re.compile(r"^车辆外廓尺寸线框 ([\d,]+) 条$")
STORYBOARD_COUNT_RE = re.compile(r"^源 [\d,]+ · 显示 65,536$")
BOUNDS_RE = re.compile(
    r"\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]"
)
Bounds = Tuple[int, int, int, int]


def load_xml(path: Path) -> tuple[ET.Element, dict[ET.Element, ET.Element]]:
    root = ET.parse(path).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    return root, parents


def values(root: ET.Element, attribute: str) -> list[str]:
    result = []
    for node in root.iter("node"):
        value = node.attrib.get(attribute, "").strip()
        if value:
            result.append(value)
    return result


def parse_bounds(value: str) -> Optional[Bounds]:
    match = BOUNDS_RE.fullmatch(value.strip())
    if not match:
        return None
    left, top, right, bottom = (int(item) for item in match.groups())
    if right <= left or bottom <= top:
        return None
    return left, top, right, bottom


def bounds_list(bounds: Optional[Bounds]) -> Optional[list[int]]:
    return list(bounds) if bounds is not None else None


def contains(outer: Bounds, inner: Bounds) -> bool:
    return (
        outer[0] <= inner[0]
        and outer[1] <= inner[1]
        and inner[2] <= outer[2]
        and inner[3] <= outer[3]
    )


def intersection(first: Bounds, second: Bounds) -> Optional[Bounds]:
    overlap = (
        max(first[0], second[0]),
        max(first[1], second[1]),
        min(first[2], second[2]),
        min(first[3], second[3]),
    )
    return overlap if overlap[0] < overlap[2] and overlap[1] < overlap[3] else None


def described_nodes(root: ET.Element, prefix: str) -> list[dict[str, object]]:
    result = []
    for node in root.iter("node"):
        description = node.attrib.get("content-desc", "").strip()
        if not description.startswith(prefix):
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        result.append(
            {
                "description": description,
                "bounds": bounds_list(bounds),
                "raw_bounds": node.attrib.get("bounds", ""),
                "_bounds": bounds,
            }
        )
    return result


def public_nodes(nodes: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        {key: value for key, value in node.items() if not key.startswith("_")}
        for node in nodes
    ]


def wireframe_nodes(root: ET.Element) -> list[dict[str, object]]:
    result = []
    for node in root.iter("node"):
        description = node.attrib.get("content-desc", "").strip()
        if not description.startswith(WIREFRAME_PREFIX):
            continue
        match = WIREFRAME_RE.fullmatch(description)
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        result.append(
            {
                "description": description,
                "line_count": int(match.group(1).replace(",", "")) if match else None,
                "bounds": bounds_list(bounds),
                "raw_bounds": node.attrib.get("bounds", ""),
                "_bounds": bounds,
            }
        )
    return result


def geometry_summary(root: ET.Element) -> dict[str, object]:
    screen_node = root.find("./node")
    screen = (
        parse_bounds(screen_node.attrib.get("bounds", ""))
        if screen_node is not None
        else None
    )
    measurements = described_nodes(root, MEASUREMENT_PREFIX)
    dimensions = described_nodes(root, DIMENSION_PREFIX)
    wireframes = wireframe_nodes(root)

    invalid_measurements = [
        {
            "description": node["description"],
            "raw_bounds": node["raw_bounds"],
        }
        for node in measurements
        if node["_bounds"] is None
    ]
    invalid_dimensions = [
        {
            "description": node["description"],
            "raw_bounds": node["raw_bounds"],
        }
        for node in dimensions
        if node["_bounds"] is None
    ]
    outside_measurements = [
        {
            "description": node["description"],
            "bounds": node["bounds"],
        }
        for node in measurements
        if node["_bounds"] is not None
        and (screen is None or not contains(screen, node["_bounds"]))
    ]
    outside_dimensions = [
        {
            "description": node["description"],
            "bounds": node["bounds"],
        }
        for node in dimensions
        if node["_bounds"] is not None
        and (screen is None or not contains(screen, node["_bounds"]))
    ]
    invalid_wireframes = [
        {
            "description": node["description"],
            "raw_bounds": node["raw_bounds"],
        }
        for node in wireframes
        if node["_bounds"] is None
    ]
    outside_wireframes = [
        {
            "description": node["description"],
            "bounds": node["bounds"],
        }
        for node in wireframes
        if node["_bounds"] is not None
        and (screen is None or not contains(screen, node["_bounds"]))
    ]

    overlaps = []
    for dimension in dimensions:
        dimension_bounds = dimension["_bounds"]
        if dimension_bounds is None:
            continue
        for measurement in measurements:
            measurement_bounds = measurement["_bounds"]
            if measurement_bounds is None:
                continue
            overlap = intersection(dimension_bounds, measurement_bounds)
            if overlap is not None:
                overlaps.append(
                    {
                        "dimension": dimension["description"],
                        "dimension_bounds": dimension["bounds"],
                        "measurement": measurement["description"],
                        "measurement_bounds": measurement["bounds"],
                        "intersection": bounds_list(overlap),
                    }
                )

    dimension_overlaps = []
    for index, first in enumerate(dimensions):
        first_bounds = first["_bounds"]
        if first_bounds is None:
            continue
        for second in dimensions[index + 1 :]:
            second_bounds = second["_bounds"]
            if second_bounds is None:
                continue
            overlap = intersection(first_bounds, second_bounds)
            if overlap is not None:
                dimension_overlaps.append(
                    {
                        "first_dimension": first["description"],
                        "first_bounds": first["bounds"],
                        "second_dimension": second["description"],
                        "second_bounds": second["bounds"],
                        "intersection": bounds_list(overlap),
                    }
                )

    measurement_descriptions = [str(node["description"]) for node in measurements]
    measurement_results_exact = Counter(measurement_descriptions) == Counter(
        EXPECTED_MEASUREMENTS
    )
    measurement_bounds_valid = (
        measurement_results_exact
        and screen is not None
        and not invalid_measurements
        and not outside_measurements
    )
    dimension_bounds_valid = (
        screen is not None
        and not invalid_dimensions
        and not outside_dimensions
    )
    wireframe_descriptions_valid = all(
        node["line_count"] is not None and int(node["line_count"]) > 0
        for node in wireframes
    )
    wireframe_bounds_valid = (
        screen is not None
        and not invalid_wireframes
        and not outside_wireframes
    )
    return {
        "screen_bounds": bounds_list(screen),
        "measurement_nodes": public_nodes(measurements),
        "measurement_results_exact": measurement_results_exact,
        "measurement_bounds_valid": measurement_bounds_valid,
        "invalid_measurement_bounds": invalid_measurements,
        "outside_measurement_bounds": outside_measurements,
        "dimension_nodes": public_nodes(dimensions),
        "dimension_bounds_valid": dimension_bounds_valid,
        "invalid_dimension_bounds": invalid_dimensions,
        "outside_dimension_bounds": outside_dimensions,
        "dimension_label_intersections": dimension_overlaps,
        "dimension_labels_overlap_free": not dimension_overlaps,
        "label_measurement_intersections": overlaps,
        "label_measurement_overlap_free": not overlaps,
        "wireframe_nodes": public_nodes(wireframes),
        "wireframe_count": len(wireframes),
        "wireframe_line_counts": [node["line_count"] for node in wireframes],
        "wireframe_descriptions_valid": wireframe_descriptions_valid,
        "wireframe_bounds_valid": wireframe_bounds_valid,
        "invalid_wireframe_bounds": invalid_wireframes,
        "outside_wireframe_bounds": outside_wireframes,
    }


def summary(path: Path) -> dict[str, object]:
    root, _ = load_xml(path)
    texts = values(root, "text")
    descriptions = values(root, "content-desc")
    dimension_labels = [value for value in descriptions if value.startswith(DIMENSION_PREFIX)]
    dimension_badges = [value for value in texts if DIMENSION_BADGE_RE.fullmatch(value)]
    measurement_results = [value for value in descriptions if value.startswith(MEASUREMENT_PREFIX)]
    storyboard_counts = [value for value in texts if STORYBOARD_COUNT_RE.fullmatch(value)]
    geometry = geometry_summary(root)
    result = {
        "texts": texts,
        "content_descriptions": descriptions,
        "dimension_labels": dimension_labels,
        "dimension_label_count": len(dimension_labels),
        "dimension_badges": dimension_badges,
        "dimension_badge_count": len(dimension_badges),
        "measurement_results": measurement_results,
        "measurement_results_complete": geometry["measurement_results_exact"],
        "storyboard_counts": storyboard_counts,
        "completed_status": EXPECTED_STATUS in texts,
    }
    result.update(geometry)
    return result


def condition_ok(name: str, data: dict[str, object]) -> bool:
    texts = data["texts"]
    labels = int(data["dimension_label_count"])
    badges = int(data["dimension_badge_count"])
    completed = bool(data["completed_status"])
    dimensions = bool(data["measurement_results_exact"])
    measurement_count = len(data["measurement_results"])
    measurement_bounds_valid = bool(data["measurement_bounds_valid"])
    wireframes = int(data["wireframe_count"])
    wireframe_descriptions_valid = bool(data["wireframe_descriptions_valid"])
    wireframe_bounds_valid = bool(data["wireframe_bounds_valid"])
    storyboard_counts = data["storyboard_counts"]
    if name == "root3d":
        return "三维扫描" in texts and "车辆外廓扫描" in texts
    if name in {"completed", "fused"}:
        return (
            completed
            and dimensions
            and labels == 0
            and badges == 0
            and measurement_bounds_valid
            and wireframes == 1
            and wireframe_descriptions_valid
            and wireframe_bounds_valid
        )
    if name == "overlay_off":
        return (
            completed
            and measurement_count == 0
            and labels == 0
            and badges == 0
            and wireframes == 0
        )
    if name == "storyboard":
        return (
            completed
            and labels == 0
            and badges == 0
            and wireframes == 0
            and measurement_count == 0
            and len(storyboard_counts) == 2
            and "镜头 A" in texts
            and "镜头 B" in texts
            and "镜头 C · 未接入" in texts
            and "镜头 D · 未接入" in texts
        )
    raise ValueError(f"未知 UI 条件：{name}")


def clickable_center(path: Path, kind: str, expected: str) -> tuple[int, int]:
    root, parents = load_xml(path)
    attribute = {"text": "text", "desc": "content-desc"}.get(kind)
    if attribute is None:
        raise ValueError(f"未知选择器类型：{kind}")
    matches = [
        node
        for node in root.iter("node")
        if node.attrib.get(attribute, "").strip() == expected
        and node.attrib.get("enabled", "true") == "true"
    ]
    for node in matches:
        target: Optional[ET.Element] = node
        fallback: Optional[ET.Element] = node
        while target is not None:
            if target.attrib.get("clickable") == "true":
                fallback = target
                break
            target = parents.get(target)
        bounds = (fallback if fallback is not None else node).attrib.get("bounds", "")
        parsed = parse_bounds(bounds)
        if parsed:
            x1, y1, x2, y2 = parsed
            return (x1 + x2) // 2, (y1 + y2) // 2
    raise LookupError(f"找不到可点击节点：{kind}={expected!r}")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("用法：ui_query.py <summary|check|point|labels> <xml> [参数]", file=sys.stderr)
        return 2
    command = argv[1]
    path = Path(argv[2])
    try:
        if command == "summary":
            print(json.dumps(summary(path), ensure_ascii=False))
            return 0
        if command == "labels":
            print(summary(path)["dimension_label_count"])
            return 0
        if command == "check":
            if len(argv) != 4:
                raise ValueError("check 需要条件名")
            data = summary(path)
            print(json.dumps(data, ensure_ascii=False))
            return 0 if condition_ok(argv[3], data) else 1
        if command == "point":
            if len(argv) != 5:
                raise ValueError("point 需要选择器类型和值")
            x, y = clickable_center(path, argv[3], argv[4])
            print(f"{x} {y}")
            return 0
        raise ValueError(f"未知命令：{command}")
    except (ET.ParseError, OSError, ValueError, LookupError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
