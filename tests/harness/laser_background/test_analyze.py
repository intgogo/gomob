#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

import numpy as np

import analyze


class AnalyzeTest(unittest.TestCase):
    def test_pipeline_filters_live_and_uses_precropped_background(self) -> None:
        scene, region, region_b_to_a, final_b_to_a = analyze.synthetic_scene()
        result = analyze.run_pipeline(
            scene["live_a"],
            scene["live_b"],
            scene["background_a_cropped"],
            scene["background_b_cropped"],
            region,
            region_b_to_a,
            final_b_to_a,
        )
        self.assertLess(len(result.live_a_region), len(scene["live_a"]))
        self.assertLess(len(result.live_b_region), len(scene["live_b"]))
        self.assertEqual(len(result.bg_a_region), len(scene["background_a_cropped"]))
        self.assertEqual(len(result.bg_b_region), len(scene["background_b_cropped"]))
        self.assertEqual(
            len(result.foreground_a) + len(result.foreground_b),
            len(result.merged),
        )
        size = analyze.span(result.merged)
        self.assertGreater(size[0], 3000)
        self.assertLess(size[2], 1800)

    def test_a_foreground_is_not_deleted_by_b_background_cross_match(self) -> None:
        identity = np.eye(4, dtype=np.float32)
        region = np.array([[-10, -10, 0], [10, -10, 0], [10, 10, 0], [-10, 10, 0]], dtype=np.float32)
        vehicle_a = np.array([[0, 0, 1]], dtype=np.float32)
        background_a = np.array([[8, 8, 8]], dtype=np.float32)
        background_b = np.array([[0, 0, 1]], dtype=np.float32)
        result = analyze.run_pipeline(
            vehicle_a,
            np.empty((0, 3), dtype=np.float32),
            background_a,
            background_b,
            region,
            identity,
            identity,
            tolerance=2,
        )
        self.assertEqual(1, len(result.foreground_a))
        self.assertTrue(np.allclose(vehicle_a, result.foreground_a))

    def test_b_region_membership_uses_final_b_to_a(self) -> None:
        angle = np.deg2rad(90)
        b_to_a = np.array(
            [[np.cos(angle), -np.sin(angle), 0, 100], [np.sin(angle), np.cos(angle), 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]],
            dtype=np.float32,
        )
        region = np.array([[90, -10, 0], [110, -10, 0], [110, 10, 0], [90, 10, 0]], dtype=np.float32)
        point_b = np.array([[0, 0, 0]], dtype=np.float32)
        kept = analyze.filter_region(point_b, region, "b", b_to_a)
        wrong = analyze.filter_region(point_b, region, "a", b_to_a)
        self.assertEqual(1, len(kept))
        self.assertEqual(0, len(wrong))

    def test_legacy_fused_requires_explicit_schema(self) -> None:
        with self.assertRaisesRegex(ValueError, "BACKGROUND_SCHEMA=legacy_fused"):
            analyze.real_inputs_from_env({"LIVE_PCD": "live.pcd", "BG_PCD": "bg.pcd"})

    def test_legacy_fused_environment_is_accepted_when_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            live = Path(directory) / "live.pcd"
            background = Path(directory) / "background.pcd"
            analyze.write_pcd(live, np.array([[0, 0, 0], [100, 0, 0]], dtype=np.float32))
            analyze.write_pcd(background, np.array([[0, 0, 0]], dtype=np.float32))
            inputs = analyze.real_inputs_from_env({
                "BACKGROUND_SCHEMA": "legacy_fused",
                "LIVE_PCD": str(live),
                "BG_PCD": str(background),
            })
            self.assertIsInstance(inputs, analyze.LegacyFusedInputs)
            warnings, errors, metrics = analyze.run_legacy_fused_real(inputs, Path(directory))
            self.assertEqual([], warnings)
            self.assertEqual([], errors)
            self.assertEqual(1, metrics["foreground_points"])

    def test_partial_real_inputs_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "输入不完整"):
            analyze.real_inputs_from_env({"LIVE_A_PCD": "a.pcd"})

    def test_region_rejects_embedded_stale_b_to_a(self) -> None:
        with self.assertRaisesRegex(ValueError, "不得携带 b_to_a"):
            analyze.parse_region(
                '{"enabled":true,"points":[[0,0,0],[1,0,0],[0,1,0]],"b_to_a":[1]}'
            )

    def test_pcd_round_trip(self) -> None:
        points = np.array([[1, 2, 3], [-4.5, 6.25, 7]], dtype=np.float32)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cloud.pcd"
            analyze.write_pcd(path, points)
            self.assertTrue(np.allclose(points, analyze.load_pcd(path)))

    def test_production_mode_requires_real_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            code, report = analyze.analyze(Path(directory), {"REQUIRE_REAL": "1"})
        self.assertEqual(1, code)
        self.assertTrue(report["require_real"])
        self.assertIsNone(report["real"])
        self.assertIn("REQUIRE_REAL=1", "；".join(report["reasons"]))

    def test_real_inputs_are_required_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            code, report = analyze.analyze(Path(directory), {})
        self.assertEqual(1, code)
        self.assertTrue(report["require_real"])


if __name__ == "__main__":
    unittest.main()
