#!/usr/bin/env python3

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np


MODULE_PATH = Path(__file__).with_name("analyze.py")
SPEC = importlib.util.spec_from_file_location("vin_consistency_analyze", MODULE_PATH)
ANALYZE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYZE)


class DirectOverlapTest(unittest.TestCase):
    def feature(self, shift_eval_x=0, angle_deg=0.0, scale=1.0):
        image = np.full((ANALYZE.CANVAS_H, ANALYZE.CANVAS_W, 3), 150, np.uint8)
        text = "LA99FRP32G0LTH013"
        font = cv2.FONT_HERSHEY_SIMPLEX
        base_size, _ = cv2.getTextSize(text, font, 1.0, 2)
        target_width = ANALYZE.CANONICAL_PITCH_PX * 16.0
        font_scale = target_width / base_size[0]
        thickness = max(2, round(font_scale * 2.0))
        text_size, _ = cv2.getTextSize(text, font, font_scale, thickness)
        origin = (
            round((ANALYZE.CANVAS_W - text_size[0]) / 2.0),
            round((ANALYZE.CANVAS_H + text_size[1]) / 2.0),
        )
        cv2.putText(
            image,
            text,
            origin,
            font,
            font_scale,
            (20, 20, 20),
            thickness,
            cv2.LINE_AA,
        )
        if shift_eval_x or angle_deg or scale != 1.0:
            matrix = cv2.getRotationMatrix2D(
                (ANALYZE.CANVAS_W / 2.0, ANALYZE.CANVAS_H / 2.0), angle_deg, scale
            )
            matrix[0, 2] += shift_eval_x / ANALYZE.OUTPUT_TO_EVAL_SCALE
            image = cv2.warpAffine(
                image,
                matrix,
                (ANALYZE.CANVAS_W, ANALYZE.CANVAS_H),
                flags=cv2.INTER_LINEAR,
                borderValue=(150, 150, 150),
            )
        return ANALYZE.image_features(ANALYZE.fixed_evaluation_image(image))

    def test_identical_fixed_coordinate_images_overlap(self):
        metrics = ANALYZE.direct_pair_metrics(self.feature(), self.feature())
        self.assertIsNotNone(metrics)
        self.assertGreater(metrics["edge_f1"], 0.99)
        self.assertLess(metrics["chamfer_px"], 0.01)
        self.assertGreater(metrics["direct_ncc"], 0.99)

    def test_shift_is_not_hidden_by_registration(self):
        metrics = ANALYZE.direct_pair_metrics(self.feature(), self.feature(shift_eval_x=32))
        self.assertIsNotNone(metrics)
        self.assertLess(metrics["edge_f1"], 0.60)
        self.assertGreater(metrics["chamfer_px"], 4.0)

    def test_rotation_is_not_hidden_by_registration(self):
        metrics = ANALYZE.direct_pair_metrics(self.feature(), self.feature(angle_deg=4.0))
        self.assertIsNotNone(metrics)
        self.assertGreater(metrics["chamfer_px"], 2.0)

    def test_scale_is_not_hidden_by_registration(self):
        metrics = ANALYZE.direct_pair_metrics(self.feature(), self.feature(scale=1.08))
        self.assertIsNotNone(metrics)
        self.assertGreater(metrics["chamfer_px"], 2.0)

    def test_fixed_evaluation_transform_is_strictly_uniform(self):
        self.assertAlmostEqual(
            ANALYZE.OUTPUT_TO_EVAL_SCALE,
            (ANALYZE.EVAL_W - 2.0 * ANALYZE.EVAL_TRANSLATE_X) / ANALYZE.CANVAS_W,
            places=12,
        )
        self.assertAlmostEqual(
            ANALYZE.OUTPUT_TO_EVAL_SCALE,
            (ANALYZE.EVAL_H - 2.0 * ANALYZE.EVAL_TRANSLATE_Y) / ANALYZE.CANVAS_H,
            places=12,
        )


class AnchorDiagnosticTest(unittest.TestCase):
    def test_height_max_relative_deviation_catches_single_outlier(self):
        heights = [250.0] * 28 + [261.0]
        self.assertLess(ANALYZE.coefficient_of_variation(heights), ANALYZE.HEIGHT_CV_MAX)
        self.assertGreater(
            ANALYZE.max_relative_deviation(heights),
            ANALYZE.HEIGHT_MAX_RELATIVE_DEVIATION_MAX,
        )

    def test_height_pitch_ratio_ignores_uniform_scale(self):
        small = ANALYZE.anchor_metrics(
            {"anchor_pitch_px": 50.0, "anchor_height_px": 70.0, "anchor_rms_px": 3.0}
        )
        large = ANALYZE.anchor_metrics(
            {"anchor_pitch_px": 100.0, "anchor_height_px": 140.0, "anchor_rms_px": 6.0}
        )
        self.assertAlmostEqual(
            small["canonical_character_height_px"],
            large["canonical_character_height_px"],
        )
        self.assertAlmostEqual(small["normalized_rms"], large["normalized_rms"])

    def test_strong_tilt_related_height_drift_fails(self):
        samples = [
            {"tilt_deg": float(tilt), "height_pitch_ratio": 1.45 - tilt * 0.02}
            for tilt in range(0, 21, 2)
        ]
        diagnostic = ANALYZE.tilt_height_diagnostic(samples)
        self.assertTrue(diagnostic["enabled"])
        self.assertTrue(diagnostic["failed"])
        self.assertLess(diagnostic["pearson_r"], -0.99)

    def test_high_correlation_with_tiny_effect_does_not_fail(self):
        samples = [
            {"tilt_deg": float(tilt), "height_pitch_ratio": 1.2 - tilt * 0.0001}
            for tilt in range(0, 21, 2)
        ]
        diagnostic = ANALYZE.tilt_height_diagnostic(samples)
        self.assertTrue(diagnostic["enabled"])
        self.assertFalse(diagnostic["failed"])

    def test_short_tilt_span_only_reports_diagnostic(self):
        samples = [
            {"tilt_deg": float(tilt), "height_pitch_ratio": 1.3 - tilt * 0.02}
            for tilt in range(5)
        ]
        diagnostic = ANALYZE.tilt_height_diagnostic(samples)
        self.assertFalse(diagnostic["enabled"])
        self.assertFalse(diagnostic["failed"])

    def test_constant_height_ratio_is_finite_and_stable(self):
        samples = [
            {"tilt_deg": float(tilt), "height_pitch_ratio": 1.25}
            for tilt in range(0, 21, 2)
        ]
        diagnostic = ANALYZE.tilt_height_diagnostic(samples)
        self.assertTrue(diagnostic["enabled"])
        self.assertFalse(diagnostic["failed"])
        self.assertEqual(diagnostic["pearson_r"], 0.0)
        self.assertEqual(diagnostic["relative_drift_over_observed_span"], 0.0)

    def test_zero_pitch_uses_json_safe_missing_normalized_rms(self):
        metrics = ANALYZE.anchor_metrics(
            {"anchor_pitch_px": 0.0, "anchor_height_px": 70.0, "anchor_rms_px": 3.0}
        )
        self.assertIsNone(metrics["normalized_rms"])

    def test_structured_restore_outcome(self):
        self.assertEqual(ANALYZE.result_outcome({"ok": True}), "success")
        self.assertEqual(
            ANALYZE.result_outcome(
                {"ok": False, "reject_reason": "text_anchor_unreliable"}
            ),
            "text_anchor_unreliable",
        )

    def test_unreliable_output_anchor_remains_in_fixed_coordinate_comparison(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            group_dir = root / "plate"
            group_dir.mkdir()
            image = np.full((ANALYZE.CANVAS_H, ANALYZE.CANVAS_W, 3), 150, np.uint8)
            for name in ("cap_a", "cap_b"):
                cv2.imwrite(str(group_dir / f"{name}.png"), image)

            output_anchor = {
                "count": 17,
                "candidate_count": 17,
                "text": "LA99FRP32G0LTH013",
                "center_x": ANALYZE.CANVAS_W / 2,
                "center_y": ANALYZE.CANVAS_H / 2,
                "pitch_px": ANALYZE.CANONICAL_PITCH_PX,
                "rms_px": 2.0,
                "mean_score": 0.95,
                "height_px": 250.0,
                "angle_deg": 0.0,
            }
            meta = {
                "anchor_count": 17,
                "anchor_candidate_count": 17,
                "anchor_pitch_px": 64.0,
                "anchor_rms_px": 2.0,
                "anchor_mean_score": 0.95,
                "anchor_height_px": 90.0,
                "anchor_rotation_deg": 0.0,
                "anchor_scale": 1.0,
                "tilt_deg": 10.0,
            }
            results = []
            for name in ("cap_a", "cap_b"):
                results.append(
                    {
                        "capture": name,
                        "ok": True,
                        "png": f"{name}.png",
                        "meta": meta,
                        "output_anchor": output_anchor,
                        "output_anchor_error": (
                            "VIN 文字格架不可靠" if name == "cap_b" else ""
                        ),
                    }
                )
            (group_dir / "results.json").write_text(
                json.dumps(results), encoding="utf-8"
            )
            report = ANALYZE.analyze_group(
                root,
                {
                    "physical_object_id": "plate",
                    "captures": [{"path": "cap_a"}, {"path": "cap_b"}],
                },
            )

            self.assertEqual(report["samples"], 2)
            self.assertTrue(
                any("cap_b: 最终图字符格架不可用" in item for item in report["failures"])
            )
            self.assertFalse(
                any("有效成功还原" in item for item in report["failures"])
            )


if __name__ == "__main__":
    unittest.main()
