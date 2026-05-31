"""prepare — 合成多视角 RGBD 打成 RgbdShot bundle zip,写盘供 e2e 上传。"""
import os, sys
import open3d as o3d

ROOT = "/root/lilw/gomob"
sys.path.insert(0, os.path.join(ROOT, "server", "fusion_service"))
sys.path.insert(0, os.path.join(ROOT, "tests", "harness", "scan_fusion"))
from rgbd_bundle import pack
import synth_dataset as synth

o3d.utility.random.seed(0)
out = os.environ.get("E2E_BUNDLE_FILE", os.path.join(ROOT, ".dev/scan_fusion_e2e/bundle.zip"))
session = os.environ.get("GOMOB_E2E_SESSION", "e2e")
os.makedirs(os.path.dirname(out), exist_ok=True)

frames, gt, exts = synth.build_dataset(n_views=10, noisy=False)
blob = pack(frames, session_key=session)
with open(out, "wb") as f:
    f.write(blob)
print(f"[prepare] bundle {len(blob)} 字节 ({len(frames)} 帧) → {out}")
