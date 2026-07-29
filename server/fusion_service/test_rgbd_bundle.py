from __future__ import annotations

import hashlib
import io
import json
import pathlib
import struct
import unittest
import warnings
import zipfile

import numpy as np
from PIL import Image

from rgbd_bundle import unpack


ROOT = pathlib.Path(__file__).resolve().parents[2]
CALIBRATION = (ROOT / "tests" / "vincreator-apk" / "VIN_BF301208.bin").read_bytes()
DW, DH, CW, CH = 640, 128, 4160, 832


def valid_bundle(calibration: bytes = CALIBRATION, extra_entries: list[tuple[str, bytes]] | None = None) -> bytes:
    rgb = np.zeros((CH, CW, 3), dtype=np.uint8)
    rgb[..., 0] = 100
    png = io.BytesIO(); Image.fromarray(rgb).save(png, "PNG")
    raw = np.full((DH, DW), 1300, dtype="<u2").tobytes()
    conf = np.full((DH, DW), 255, dtype=np.uint8).tobytes()
    manifest = {
        "schema_version": 2,
        "session_key": "scan-test",
        "calibration": {
            "format": "vin_creator_v3",
            "depth_device_id": "BF301208", "color_device_id": "202303111518",
            "depth_profile": "640x128_mode25", "color_profile": "4160x832",
            "sha256": hashlib.sha256(calibration).hexdigest(),
        },
        "source": {
            "depth_width": DW, "depth_height": DH,
            "depth_encoding": "vin_creator_disparity_u16",
            "color_width": CW, "color_height": CH,
        },
        "shots": [{
            "index": 0, "rgb": "rgb_0.png", "depth": "depth_0.u16", "conf": "conf_0.u8",
            "color_timestamp_us": 1_000_000, "depth_timestamp_us": 1_005_000,
        }],
    }
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest))
        z.writestr("calibration.bin", calibration)
        z.writestr("rgb_0.png", png.getvalue())
        z.writestr("depth_0.u16", raw)
        z.writestr("conf_0.u8", conf)
        for name, value in extra_entries or []:
            z.writestr(name, value)
    return out.getvalue()


def rewrite_manifest(blob: bytes, mutate) -> bytes:
    with zipfile.ZipFile(io.BytesIO(blob), "r") as source:
        manifest = json.loads(source.read("manifest.json"))
        mutate(manifest)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                payload = json.dumps(manifest).encode() if item.filename == "manifest.json" else source.read(item.filename)
                target.writestr(item.filename, payload)
    return out.getvalue()


def rewrite_entry(blob: bytes, name: str, payload: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(blob), "r") as source:
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                target.writestr(item.filename, payload if item.filename == name else source.read(item.filename))
    return out.getvalue()


class RgbdBundleTest(unittest.TestCase):
    def test_unpack_aligns_raw_rgbd(self) -> None:
        frames = unpack(valid_bundle())
        self.assertEqual(1, len(frames))
        frame = frames[0]
        self.assertEqual((DH, DW, 3), frame.color.shape)
        self.assertEqual((DH, DW), frame.depth_mm.shape)
        self.assertAlmostEqual(378.1375, float(frame.depth_mm[65, 324]), places=3)
        self.assertAlmostEqual(378.1375, float(frame.depth_mm[0, 0]), places=3)
        self.assertEqual(0, int(frame.color[0, 0].sum()))
        self.assertAlmostEqual(614.60498046875, frame.intr.fx, places=9)
        self.assertEqual(255, int(frame.conf[65, 324]))

    def test_rejects_missing_duplicate_truncated_and_tampered_calibration(self) -> None:
        missing = valid_bundle(extra_entries=[])
        with zipfile.ZipFile(io.BytesIO(missing), "r") as source:
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
                for item in source.infolist():
                    if item.filename != "calibration.bin":
                        target.writestr(item.filename, source.read(item.filename))
            missing = out.getvalue()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            duplicate = valid_bundle(extra_entries=[("calibration.bin", CALIBRATION)])
        truncated = valid_bundle(CALIBRATION[:-1])
        wrong_serial = b"BF999999" + CALIBRATION[8:]
        tampered = bytearray(CALIBRATION)
        struct.pack_into("<d", tampered, 0x294 + 88, struct.unpack_from("<d", tampered, 0x294 + 88)[0] + 0.1)
        sha_mismatch = bytearray(valid_bundle())
        with zipfile.ZipFile(io.BytesIO(sha_mismatch), "r") as source:
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
                for item in source.infolist():
                    payload = source.read(item.filename)
                    if item.filename == "calibration.bin":
                        payload = bytes([payload[0] ^ 0x01]) + payload[1:]
                    target.writestr(item.filename, payload)
            sha_mismatch = out.getvalue()
        for name, blob in [
            ("missing", missing),
            ("duplicate", duplicate),
            ("truncated", truncated),
            ("serial_mismatch", valid_bundle(wrong_serial)),
            ("tampered", valid_bundle(bytes(tampered))),
            ("sha_mismatch", bytes(sha_mismatch)),
        ]:
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    unpack(blob)

    def test_rejects_unexpected_entry(self) -> None:
        with self.assertRaisesRegex(ValueError, "契约外"):
            unpack(valid_bundle(extra_entries=[("VIN_BF301208.bin", CALIBRATION)]))

    def test_rejects_non_unique_manifest_fields_and_invalid_timestamps(self) -> None:
        cases = [
            rewrite_manifest(valid_bundle(), lambda m: m["calibration"].update({"version": 3})),
            rewrite_manifest(valid_bundle(), lambda m: m["shots"][0].pop("conf")),
            rewrite_manifest(valid_bundle(), lambda m: m["shots"][0].update({"depth_timestamp_us": 1_020_001})),
            rewrite_manifest(valid_bundle(), lambda m: m.update({"schema_version": 2.0})),
        ]
        for blob in cases:
            with self.subTest():
                with self.assertRaises(ValueError):
                    unpack(blob)

    def test_rejects_rgb_actual_size_mismatch_before_pixel_decode(self) -> None:
        png = io.BytesIO()
        Image.new("RGB", (64, 64)).save(png, "PNG")
        with self.assertRaisesRegex(ValueError, "RGB 实际尺寸"):
            unpack(rewrite_entry(valid_bundle(), "rgb_0.png", png.getvalue()))


if __name__ == "__main__":
    unittest.main()
