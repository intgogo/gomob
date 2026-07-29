"""prepare — 生成可经 calibration.bin 精确配准的多视角 schema v2 bundle。"""
import os
import pathlib

from vin_synth_dataset import bundle

ROOT = pathlib.Path(__file__).resolve().parents[3]
out = os.environ.get("E2E_BUNDLE_FILE", str(ROOT / ".dev" / "scan_fusion_e2e" / "bundle.zip"))
session = os.environ.get("GOMOB_E2E_SESSION", "e2e")
os.makedirs(os.path.dirname(out), exist_ok=True)

blob = bundle(session, n_views=10)
with open(out, "wb") as f:
    f.write(blob)
print(f"[prepare] schema v2 VIN 多视角 bundle {len(blob)} 字节（10 帧）→ {out}")
