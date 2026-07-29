from __future__ import annotations

import hashlib
import pathlib
import struct
import unittest

import numpy as np

from vin_calibration import CalibrationError, parse_calibration, project_disparity


ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests" / "vincreator-apk" / "VIN_BF301208.bin"


class VinCalibrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.blob = FIXTURE.read_bytes()
        self.sha = hashlib.sha256(self.blob).hexdigest()

    def test_parse_and_depth_intrinsics_match_go_oracle(self) -> None:
        calibration = parse_calibration(self.blob, self.sha)
        self.assertEqual("BF301208", calibration.depth_device_id)
        self.assertEqual(3, calibration.version)
        fx, fy, cx, cy = calibration.depth_intrinsics(640, 128)
        self.assertAlmostEqual(614.60498046875, fx, places=9)
        self.assertAlmostEqual(614.60498046875, fy, places=9)
        self.assertAlmostEqual(324.0, cx, places=9)
        self.assertAlmostEqual(65.43250274658203, cy, places=9)

    def test_projection_matches_go_native_vectors(self) -> None:
        calibration = parse_calibration(self.blob, self.sha)
        raw = np.zeros((128, 640), dtype=np.uint16)
        vectors = [
            (324, 65, 279.38392092918923, 62.21943173082844),
            (344, 55, 306.56828818086933, 48.60381214126974),
            (304, 75, 252.1950535978917, 75.82674330390832),
        ]
        for column, row, _, _ in vectors:
            raw[row, column] = 1300
        depth, color_column, color_row, valid = project_disparity(raw, calibration)
        for column, row, expected_x, expected_y in vectors:
            self.assertTrue(valid[row, column])
            self.assertAlmostEqual(378.13750906277164, depth[row, column], places=9)
            self.assertAlmostEqual(expected_x, color_column[row, column] * 640 / 4160, places=9)
            self.assertAlmostEqual(expected_y, color_row[row, column] * 128 / 832, places=9)

    def test_projection_keeps_vehicle_points_beyond_one_meter(self) -> None:
        calibration = parse_calibration(self.blob, self.sha)
        raw = np.zeros((128, 640), dtype=np.uint16)
        raw[65, 324] = 400
        depth, _, _, valid = project_disparity(raw, calibration)
        self.assertTrue(valid[65, 324])
        self.assertGreater(float(depth[65, 324]), 1000.0)

    def test_rejects_truncated_tampered_identity_and_version(self) -> None:
        duplicate_euler = bytearray(self.blob)
        struct.pack_into("<d", duplicate_euler, 0x294 + 88, struct.unpack_from("<d", duplicate_euler, 0x294 + 88)[0] + 0.1)
        mutations = [
            self.blob[:-1],
            b"XX$99999" + self.blob[8:],
            self.blob[:0x200] + (2).to_bytes(4, "little") + self.blob[0x204:],
            bytes(duplicate_euler),
        ]
        for mutated in mutations:
            with self.subTest(size=len(mutated)):
                with self.assertRaises(CalibrationError):
                    parse_calibration(mutated, hashlib.sha256(mutated).hexdigest())

    def test_accepts_a_valid_non_bf_device_id(self) -> None:
        mutated = b"AA_12345" + self.blob[8:]
        calibration = parse_calibration(mutated, hashlib.sha256(mutated).hexdigest())
        self.assertEqual("AA_12345", calibration.depth_device_id)


if __name__ == "__main__":
    unittest.main()
