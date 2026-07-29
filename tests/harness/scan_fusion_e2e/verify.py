"""verify — 回读 GLB，并对 calibration.bin 往返后的观测面做 chamfer 复核。"""
import os
import pathlib
import sys
import numpy as np
import open3d as o3d
import trimesh

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tests" / "harness" / "scan_fusion"))
import synth_dataset as synth  # noqa: E402
from vin_synth_dataset import build_dataset  # noqa: E402

o3d.utility.random.seed(0)
glb_path = os.environ.get("E2E_RESULT_FILE", str(ROOT / ".dev" / "scan_fusion_e2e" / "result.glb"))
if not os.path.exists(glb_path) or os.path.getsize(glb_path) == 0:
    print(f"[verify] ✗ 无 GLB 产出:{glb_path}")
    sys.exit(1)

# 回读 GLB(经完整 端→MinIO→worker→service→MinIO 链路产出)
scene = trimesh.load(glb_path, file_type="glb")
geo = scene.geometry[list(scene.geometry)[0]] if hasattr(scene, "geometry") and scene.geometry else scene
V = np.asarray(geo.vertices)
F = np.asarray(geo.faces)
print(f"[verify] GLB 回读:顶点={len(V)} 面={len(F)}")
if len(V) < 1000 or len(F) < 1000:
    print("[verify] ✗ GLB mesh 过小")
    sys.exit(1)

# 重建同一合成数据集(确定性)→ 观测面参考 + GT 对齐外参
frames, _, _, exts = build_dataset(n_views=10)
ref = synth.observed_surface(frames, exts)

mesh = o3d.geometry.TriangleMesh(
    o3d.utility.Vector3dVector(V), o3d.utility.Vector3iVector(F))
mesh.transform(np.linalg.inv(exts[0]))     # cam0 帧 → GT 帧
fp = mesh.sample_points_uniformly(80000)
d_fg = np.asarray(fp.compute_point_cloud_distance(ref)).mean()
d_gf = np.asarray(ref.compute_point_cloud_distance(fp)).mean()
cham = (d_fg + d_gf) / 2 * 1000
ok = cham <= 5.0
print(f"[verify] chamfer(端到端 GLB vs 观测面)= {cham:.2f}mm  {'✓ ≤5mm' if ok else '✗ >5mm'}")
print("E2E_VERIFY_OK" if ok else "E2E_VERIFY_FAIL")
sys.exit(0 if ok else 1)
