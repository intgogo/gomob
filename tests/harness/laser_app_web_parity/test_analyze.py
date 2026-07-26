#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

import analyze


def complete_result() -> dict:
    return {
        "session_key": "session-207",
        "result_object_key": "laser-scans/session-207/fused.pcd",
        "unit_a_object_key": "laser-scans/session-207/unit_a.pcd",
        "unit_b_object_key": "laser-scans/session-207/unit_b.pcd",
        "measured_object_key": "laser-scans/session-207/measured.pcd",
        "points": 2049840,
        "pts_a": 1024510,
        "pts_b": 1025330,
        "align_method": "site",
        "site_revision": "site-sha",
        "region_revision": "region-sha",
        "measure_mode": "bg_subtract",
        "measure_valid": True,
        "length_mm": 1768.0,
        "width_mm": 531.0,
        "height_mm": 763.0,
        "compliant": True,
        "background_set": True,
        "background_compatible": True,
        "background_reason": "ready",
        "background_revision_id": 301,
        "background_schema": "raw_unit_frames_v1",
        "fg_points": 123456,
        "measured_points": 123456,
        "axle": {"count": 4},
        "cargo_box": {"valid": True},
        "overlay": {"vehicle_box": {}},
        "ground_nx": 0.01,
        "ground_ny": -0.02,
        "ground_nz": 0.9997,
        "ground_d": -123.0,
        "ground_valid": True,
    }


class AnalyzeTest(unittest.TestCase):
    def test_complete_shape(self) -> None:
        self.assertEqual([], analyze.validate_result_shape(complete_result()))

    def test_dimensions_object_must_contain_each_axis(self) -> None:
        result = complete_result()
        result.pop("length_mm")
        result.pop("width_mm")
        result.pop("height_mm")
        result["dimensions"] = {"length_mm": 1768.0}
        self.assertEqual(["车宽", "车高"], analyze.validate_result_shape(result))

    def test_ground_requires_all_coefficients(self) -> None:
        result = complete_result()
        result.pop("ground_d")
        self.assertEqual(["地面 d"], analyze.validate_result_shape(result))

    def test_bool_and_number_are_not_equal(self) -> None:
        self.assertTrue(analyze.compare({"valid": True}, {"valid": 1}))

    def test_revisions_reject_bool_zero_and_blank(self) -> None:
        for value in (None, False, True, 0, -1, "", "  "):
            self.assertFalse(analyze.nonempty(value), value)
        for value in (1, 7, "abc123"):
            self.assertTrue(analyze.nonempty(value), value)

    def test_read_records_rejects_duplicates(self) -> None:
        payload = {"client": "web", "effective": {}, "result": complete_result()}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "log.txt"
            line = analyze.PREFIX + " " + json.dumps(payload)
            path.write_text(line + "\n" + line + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "重复"):
                analyze.read_records(path)

    def test_required_client_contract_markers_are_explicit(self) -> None:
        self.assertEqual(("web", "app", "measured_pcd"), analyze.CLIENT_CONTRACTS)


if __name__ == "__main__":
    unittest.main()
