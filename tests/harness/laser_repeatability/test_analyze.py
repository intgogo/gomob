#!/usr/bin/env python3

from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import analyze


def row(
    job_id: int,
    *,
    site: str = "site-new",
    region: str = "region-new",
    background: int = 25,
    length: float = 1768,
    width: float = 531,
    height: float = 763,
    ground: str = "background_revision",
) -> dict:
    return {
        "id": job_id,
        "inspection_id": 9001,
        "mode": "bg_subtract",
        "valid": True,
        "l": length,
        "w": width,
        "h": height,
        "site_revision": site,
        "region_revision": region,
        "background_revision": background,
        "background_schema": "raw_unit_frames_v1",
        "ground_source": ground,
        "ground_stable": True,
        "ground_reason": "ready",
        "ground_valid": True,
        "measured_points": 548996,
        "measured_object_key": f"laser/{job_id}/measured.pcd",
        "artifact_xyz_sha256": "a" * 64,
        "artifact_coordinate_schema": "unit_a_world_mm_v1",
        "artifact_source_points": 548996,
        "artifact_site_revision": site,
        "artifact_region_revision": region,
        "artifact_background_revision": background,
        "artifact_final_b_to_a_sha256": "b" * 64,
        "refine_applied": True,
        "refine_accepted": True,
        "refine_dt": 20,
        "refine_dr": 0.2,
    }


class AnalyzeTest(unittest.TestCase):
    def analyze(self, rows: list[dict]) -> tuple[int, str]:
        output = io.StringIO()
        with redirect_stdout(output):
            code = analyze.analyze_rows(rows)
        return code, output.getvalue()

    def test_different_revisions_and_legacy_rows_never_mix(self) -> None:
        rows = [
            row(210, length=1768, width=531, height=763),
            row(209, length=1770, width=530, height=764),
            row(208, length=1767, width=532, height=762),
            row(207, site="site-old", length=3558, width=144, height=3107),
            row(206, region="region-old", length=3400, width=200, height=2800),
            row(205, background=24, length=3300, width=180, height=2700),
            {
                **row(204, length=3200, width=160, height=2600),
                "site_revision": None,
                "region_revision": None,
                "background_revision": None,
            },
        ]
        code, output = self.analyze(rows)
        self.assertEqual(0, code, output)
        self.assertIn("本组有效测量 3 次", output)
        self.assertIn("隔离其他 revision/旧链 4 次", output)
        self.assertIn("车长: 均值 1768.3mm", output)
        self.assertNotIn("均值 2", output)
        self.assertIn("结论: 正常", output)

    def test_latest_legacy_row_is_rejected_instead_of_falling_back(self) -> None:
        latest = row(210)
        latest["background_schema"] = "legacy_fused"
        code, output = self.analyze([latest, row(209), row(208), row(207)])
        self.assertEqual(1, code)
        self.assertIn("拒绝回退混算旧链", output)

    def test_background_revision_and_persisted_ground_are_both_stable(self) -> None:
        rows = [row(3, ground="background_revision"), row(2, ground="persisted"), row(1)]
        code, output = self.analyze(rows)
        self.assertEqual(0, code, output)
        self.assertNotIn("地面来源不是", output)

    def test_refit_ground_warns(self) -> None:
        code, output = self.analyze([row(3, ground="refit"), row(2), row(1)])
        self.assertEqual(0, code)
        self.assertIn("逐扫描 refit 会重新引入方差", output)
        self.assertIn("结论: 警告", output)

    def test_claimed_valid_measurement_without_ground_gate_is_an_error(self) -> None:
        broken = row(3)
        broken["ground_stable"] = False
        broken["ground_reason"] = "ground_refit_invalid"
        code, output = self.analyze([broken, row(2), row(1)])
        self.assertEqual(1, code)
        self.assertIn("地面生产门", output)
        self.assertIn("结论: 异常", output)

    def test_directory_input_resolves_stats_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stats.jsonl"
            path.write_text("\n".join(json.dumps(row(job_id)) for job_id in (3, 2, 1)), encoding="utf-8")
            output = io.StringIO()
            with patch.dict("os.environ", {"REQUIRE_PRODUCTION": "0"}, clear=False), redirect_stdout(output):
                code = analyze.main([directory])
            self.assertEqual(0, code, output.getvalue())

    def test_main_requires_production_evidence_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stats.jsonl"
            path.write_text("\n".join(json.dumps(row(job_id)) for job_id in (3, 2, 1)), encoding="utf-8")
            output = io.StringIO()
            with patch.dict("os.environ", {}, clear=True), redirect_stdout(output):
                code = analyze.main([directory])
        self.assertEqual(1, code)
        self.assertIn("未设置 GOMOB_LASER_TRUTH_LWH", output.getvalue())

    def test_production_mode_requires_three_samples_and_truth(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            no_truth = analyze.analyze_rows(
                [row(3), row(2), row(1)],
                require_production=True,
            )
        self.assertEqual(1, no_truth)
        self.assertIn("未设置 GOMOB_LASER_TRUTH_LWH", output.getvalue())

        output = io.StringIO()
        with redirect_stdout(output):
            too_few = analyze.analyze_rows(
                [row(2), row(1)],
                truth=(1768, 531, 763),
                require_production=True,
            )
        self.assertEqual(1, too_few)
        self.assertIn("不足 3 次", output.getvalue())

    def test_production_mode_rejects_warning(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            code = analyze.analyze_rows(
                [row(3, ground="refit"), row(2), row(1)],
                truth=(1768, 531, 763),
                require_production=True,
            )
        self.assertEqual(1, code)
        self.assertIn("结论: 警告", output.getvalue())

    def test_different_inspections_never_mix_as_repeatability(self) -> None:
        rows = [row(3), row(2), row(1)]
        rows[1]["inspection_id"] = 9002
        code, output = self.analyze(rows)
        self.assertEqual(0, code, output)
        self.assertIn("本组有效测量 2 次", output)
        self.assertIn("隔离其他 revision/旧链 1 次", output)

    def test_claimed_valid_measurement_requires_canonical_artifact(self) -> None:
        broken = row(3)
        broken["artifact_source_points"] = 1
        code, output = self.analyze([broken, row(2), row(1)])
        self.assertEqual(1, code)
        self.assertIn("canonical measured 制品", output)

    def test_claimed_valid_measurement_requires_refine_production_gate(self) -> None:
        broken = row(3)
        broken["refine_dt"] = 50.1
        code, output = self.analyze([broken, row(2), row(1)])
        self.assertEqual(1, code)
        self.assertIn("精修生产门", output)


if __name__ == "__main__":
    unittest.main()
