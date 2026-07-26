#!/usr/bin/env python3
"""按同一工位配置 revision 判定激光外廓重复性与准确度。

输入是 ``run.sh`` 生成的 ``stats.jsonl``，按 job id 倒序。只有最新车辆任务的
``measure_mode + site + region + background`` 四元组会进入统计；缺 revision、旧
``legacy_fused`` 背景或不同 revision 的历史任务只做诊断，绝不混入均值和方差。
"""

from __future__ import annotations

import json
import math
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping


EXPECTED_MODE = "bg_subtract"
EXPECTED_BACKGROUND_SCHEMA = "raw_unit_frames_v1"
VALID_GROUND_SOURCES = frozenset(("background_revision", "persisted"))
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class RevisionKey:
    mode: str
    site: str
    region: str
    background: int
    inspection: int

    def label(self) -> str:
        return (
            f"mode={self.mode} site={short_revision(self.site)} "
            f"region={short_revision(self.region)} background={self.background} "
            f"inspection={self.inspection}"
        )


def short_revision(value: str) -> str:
    return value if len(value) <= 12 else value[:12] + "…"


def nonblank_string(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value or None


def positive_int(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if number > 0 else None


def finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if math.isfinite(number) else None


def revision_key(row: Mapping[str, Any]) -> RevisionKey | None:
    mode = nonblank_string(row.get("mode"))
    site = nonblank_string(row.get("site_revision"))
    region = nonblank_string(row.get("region_revision"))
    background = positive_int(row.get("background_revision"))
    inspection = positive_int(row.get("inspection_id"))
    schema = nonblank_string(row.get("background_schema"))
    if (
        mode != EXPECTED_MODE
        or site is None
        or region is None
        or background is None
        or inspection is None
        or schema != EXPECTED_BACKGROUND_SCHEMA
    ):
        return None
    return RevisionKey(mode, site, region, background, inspection)


def dimensions_valid(row: Mapping[str, Any]) -> bool:
    return all(finite_number(row.get(key)) is not None for key in ("l", "w", "h"))


def ground_health_valid(row: Mapping[str, Any]) -> bool:
    reason = nonblank_string(row.get("ground_reason"))
    return (
        row.get("ground_stable") is True
        and row.get("ground_valid") is True
        and reason in (None, "ready")
    )


def measured_artifact_valid(row: Mapping[str, Any]) -> bool:
    measured_points = positive_int(row.get("measured_points"))
    source_points = positive_int(row.get("artifact_source_points"))
    xyz_sha256 = nonblank_string(row.get("artifact_xyz_sha256"))
    final_b_to_a_sha256 = nonblank_string(row.get("artifact_final_b_to_a_sha256"))
    return (
        nonblank_string(row.get("measured_object_key")) is not None
        and measured_points is not None
        and source_points == measured_points
        and nonblank_string(row.get("artifact_coordinate_schema")) == "unit_a_world_mm_v1"
        and xyz_sha256 is not None
        and SHA256_HEX.fullmatch(xyz_sha256) is not None
        and final_b_to_a_sha256 is not None
        and SHA256_HEX.fullmatch(final_b_to_a_sha256) is not None
        and nonblank_string(row.get("artifact_site_revision")) == nonblank_string(row.get("site_revision"))
        and nonblank_string(row.get("artifact_region_revision")) == nonblank_string(row.get("region_revision"))
        and positive_int(row.get("artifact_background_revision")) == positive_int(row.get("background_revision"))
    )


def refine_health_valid(row: Mapping[str, Any]) -> bool:
    delta_translation = finite_number(row.get("refine_dt"))
    delta_rotation = finite_number(row.get("refine_dr"))
    return (
        row.get("refine_applied") is True
        and row.get("refine_accepted") is True
        and delta_translation is not None
        and delta_translation <= 50
        and delta_rotation is not None
        and abs(delta_rotation) <= 1
    )


def load_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"第 {line_number} 行不是 JSON 对象")
            rows.append(value)
    return rows


def parse_truth(raw: str) -> tuple[float, float, float] | None:
    if not raw.strip():
        return None
    parts = raw.split(",")
    if len(parts) != 3:
        raise ValueError("GOMOB_LASER_TRUTH_LWH 必须是 L,W,H 三个毫米值")
    values = tuple(float(part.strip()) for part in parts)
    if any(not math.isfinite(value) or value <= 0 for value in values):
        raise ValueError("GOMOB_LASER_TRUTH_LWH 必须是三个正有限数")
    return values  # type: ignore[return-value]


def worst(left: str, right: str) -> str:
    order = {"正常": 0, "警告": 1, "异常": 2}
    return left if order[left] >= order[right] else right


def analyze_rows(
    rows: list[dict[str, Any]],
    truth: tuple[float, float, float] | None = None,
    emit: Callable[[str], None] = print,
    require_production: bool = False,
) -> int:
    if not rows:
        emit("异常: 无采样数据（该工位还没有车辆扫描结果）")
        return 1

    newest = rows[0]
    selected = revision_key(newest)
    if selected is None:
        emit(
            "异常: 最新车辆任务没有完整生产链 revision，拒绝回退混算旧链："
            f"job={newest.get('id')} mode={newest.get('mode')!r} "
            f"site={newest.get('site_revision')!r} region={newest.get('region_revision')!r} "
            f"background={newest.get('background_revision')!r} "
            f"schema={newest.get('background_schema')!r} inspection={newest.get('inspection_id')!r}"
        )
        return 1

    complete_counts: Counter[RevisionKey] = Counter()
    for row in rows:
        key = revision_key(row)
        if key is not None:
            complete_counts[key] += 1
    legacy_count = sum(1 for row in rows if revision_key(row) is None)
    emit(f"最新生产链: {selected.label()}")
    emit(
        "revision 分组: "
        + "；".join(f"[{key.label()}] {count} 条" for key, count in complete_counts.items())
        + (f"；旧链/不完整 {legacy_count} 条" if legacy_count else "")
    )

    same_revision = [row for row in rows if revision_key(row) == selected]
    claimed_valid_rows = [row for row in same_revision if row.get("valid") is True and dimensions_valid(row)]
    unsafe_ground_rows = [row for row in claimed_valid_rows if not ground_health_valid(row)]
    unsafe_artifact_rows = [row for row in claimed_valid_rows if not measured_artifact_valid(row)]
    unsafe_refine_rows = [row for row in claimed_valid_rows if not refine_health_valid(row)]
    valid_rows = [
        row for row in claimed_valid_rows
        if ground_health_valid(row) and measured_artifact_valid(row) and refine_health_valid(row)
    ]
    invalid_count = len(same_revision) - len(valid_rows)
    other_count = len(rows) - len(same_revision)
    emit(
        f"本组有效测量 {len(valid_rows)} 次"
        + (f"，同 revision 无效/尺寸缺失 {invalid_count} 次" if invalid_count else "")
        + (f"，隔离其他 revision/旧链 {other_count} 次" if other_count else "")
    )

    verdict = "正常"
    reasons: list[str] = []
    if unsafe_ground_rows:
        details = sorted(
            {
                f"job={row.get('id')} stable={row.get('ground_stable')!r} "
                f"valid={row.get('ground_valid')!r} reason={row.get('ground_reason')!r}"
                for row in unsafe_ground_rows
            }
        )
        emit("异常: 存在服务端声称有效但未通过地面生产门的样本：" + "；".join(details))
        verdict = "异常"
        reasons.append("地面生产门失真")
    if unsafe_artifact_rows:
        emit(
            "异常: 存在服务端声称有效但 canonical measured 制品不完整/错配的样本："
            + "、".join(str(row.get("id")) for row in unsafe_artifact_rows)
        )
        verdict = "异常"
        reasons.append("measured 制品失真")
    if unsafe_refine_rows:
        emit(
            "异常: 存在服务端声称有效但未满足精修生产门(≤50mm/1°)的样本："
            + "、".join(str(row.get("id")) for row in unsafe_refine_rows)
        )
        verdict = "异常"
        reasons.append("精修生产门失真")
    if len(valid_rows) < 3:
        level = "异常" if require_production else "警告"
        emit(f"{level}: 同 revision 有效测量不足 3 次({len(valid_rows)})，无法判重复性")
        verdict = worst(verdict, level)
        reasons.append("同 revision 样本不足")
    else:
        for key, name in (("l", "车长"), ("w", "车宽"), ("h", "车高")):
            values = [float(row[key]) for row in valid_rows]
            mean = sum(values) / len(values)
            deviation = math.sqrt(sum((value - mean) ** 2 for value in values) / len(values))
            value_range = max(values) - min(values)
            level = (
                "正常"
                if deviation <= 5 and value_range <= 15
                else ("警告" if deviation <= 10 and value_range <= 30 else "异常")
            )
            emit(
                f"  {name}: 均值 {mean:.1f}mm σ={deviation:.1f}mm "
                f"极差={value_range:.1f}mm → {level}"
            )
            if level != "正常":
                reasons.append(f"{name}重复性{level}(σ={deviation:.1f}/极差={value_range:.1f})")
                verdict = worst(verdict, level)

        if truth is None:
            if require_production:
                emit("异常: REQUIRE_PRODUCTION=1 但未设置 GOMOB_LASER_TRUTH_LWH")
                verdict = "异常"
                reasons.append("缺少准确度真值")
            else:
                emit("  (未设 GOMOB_LASER_TRUTH_LWH，跳过准确度判定)")
        else:
            for key, name, reference in zip(("l", "w", "h"), ("车长", "车宽", "车高"), truth):
                values = [float(row[key]) for row in valid_rows]
                mean = sum(values) / len(values)
                error_percent = abs(mean - reference) / reference * 100
                level = "正常" if error_percent <= 1 else ("警告" if error_percent <= 2 else "异常")
                emit(
                    f"  {name} vs 真值{reference:.0f}: 偏差 {mean - reference:+.1f}mm "
                    f"({error_percent:.2f}%) → {level}"
                )
                if level != "正常":
                    reasons.append(f"{name}准确度{level}({error_percent:.2f}%)")
                    verdict = worst(verdict, level)

    if valid_rows:
        bad_ground = sorted(
            {str(row.get("ground_source") or "<空>") for row in valid_rows}
            - VALID_GROUND_SOURCES
        )
        if bad_ground:
            emit(
                "警告: 地面来源不是 background_revision/persisted："
                + "、".join(bad_ground)
                + "；逐扫描 refit 会重新引入方差"
            )
            verdict = worst(verdict, "警告")
            reasons.append("地面来源不稳定")

    suffix = f"（{'；'.join(reasons)}）" if reasons else "（同 revision 重复性达标）"
    emit(f"\n结论: {verdict}{suffix}")
    return 1 if verdict == "异常" or (require_production and verdict != "正常") else 0


def resolve_input_path(argument: str | None) -> Path:
    path = Path(argument or ".dev/laser_repeatability/stats.jsonl")
    return path / "stats.jsonl" if path.is_dir() else path


def main(argv: Iterable[str] | None = None) -> int:
    args = list(argv if argv is not None else sys.argv[1:])
    path = resolve_input_path(args[0] if args else None)
    try:
        rows = load_rows(path)
        truth = parse_truth(os.environ.get("GOMOB_LASER_TRUTH_LWH", ""))
        require_production = os.environ.get("REQUIRE_PRODUCTION", "1").strip().lower() in (
            "1", "true", "yes", "on"
        )
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"异常: 读取重复性样本失败：{exc}")
        return 1
    return analyze_rows(rows, truth, require_production=require_production)


if __name__ == "__main__":
    raise SystemExit(main())
