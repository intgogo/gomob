#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import analyze


PASS_LOG = """\
07-16 15:00:00.000 100 101 I eys3d_vcpp: tick cbFrames=10 state=2
07-16 15:00:00.000 100 102 I hlsd8_uvc: tick color=20 帧
07-16 15:00:02.000 100 101 I eys3d_vcpp: ourCb #20 depth 640x128 color 1280x256 serial=20 centerDispX8=1800 valid=72.0% max=2044 meanNZ=1700
07-16 15:00:02.000 100 102 I hlsd8_uvc: tick color=30 帧
"""


class AnalyzeTest(unittest.TestCase):
    def write_sample(self, directory, text=PASS_LOG, duration=2.0):
        root = Path(directory)
        (root / "logcat.txt").write_text(text, encoding="utf-8")
        (root / "sample.json").write_text(
            json.dumps(
                {
                    "duration_seconds": duration,
                    "requested_seconds": duration,
                    "device_serial": "device",
                    "log_file": "logcat.txt",
                }
            ),
            encoding="utf-8",
        )

    def test_directory_input_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_sample(directory)
            self.assertEqual(analyze.PASS, analyze.main([directory]))

    def test_warning_uses_contract_exit_code(self):
        log = PASS_LOG.replace("cbFrames=10", "cbFrames=18").replace(
            "valid=72.0%", "valid=20.0%"
        )
        with tempfile.TemporaryDirectory() as directory:
            self.write_sample(directory, log)
            self.assertEqual(analyze.WARN, analyze.main([directory]))

    def test_missing_log_is_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sample.json").write_text(
                json.dumps({"duration_seconds": 2.0, "log_file": "logcat.txt"}),
                encoding="utf-8",
            )
            self.assertEqual(analyze.FAIL, analyze.main([directory]))

    def test_no_frames_is_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_sample(directory, "07-16 15:00:00.000 I eys3d_vcpp: vendor C++ 直驱会话\n")
            self.assertEqual(analyze.FAIL, analyze.main([directory]))

    def test_java_frame_path_is_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_sample(directory, PASS_LOG + "I ApcCamera: IFrameCallback\n")
            self.assertEqual(analyze.FAIL, analyze.main([directory]))

    def test_sample_duration_controls_single_counter_fallback(self):
        log = """\
07-16 15:00:00.000 I eys3d_vcpp: vendor C++ 直驱会话
07-16 15:00:00.000 I eys3d_vcpp: FrameGrabber 校验通过 cb==livePlyCallback
07-16 15:00:00.000 I eys3d_vcpp: tick cbFrames=30 state=2
07-16 15:00:00.000 I hlsd8_uvc: tick color=30 帧
"""
        with tempfile.TemporaryDirectory() as directory:
            self.write_sample(directory, log, duration=10.0)
            self.assertEqual(analyze.PASS, analyze.main([directory]))

    def test_cli_accepts_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_sample(directory)
            result = subprocess.run(
                [sys.executable, str(Path(analyze.__file__)), directory],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("PASS:", result.stdout)


if __name__ == "__main__":
    unittest.main()
