"""fusion_service — 多视角 RGBD 云端融合 HTTP 服务(Open3D)。

形态对标 asr_service(Python FastAPI 重计算服务):Go 侧 gomob-fusionworker 经 DB 队列领任务、
从 MinIO 拉 RgbdShot bundle zip,POST 到本服务 /fuse 跑 Open3D 融合,拿回 GLB。算法在 fusion_core。

/fuse 契约:
  入:multipart,file 字段 `bundle`=RgbdShot zip(见 rgbd_bundle.py);
     可选 form:conf_threshold(int,默认 80)、enable_confidence(bool,默认 true)、voxel_size_mm(float,默认 6)、
     mask_erode_px(int,默认 0;bundle 带目标 mask 时的边界腐蚀,真机飞点用)、texture(bool)、tex_size(int)。
  出:body=GLB 字节(model/gltf-binary);stats 走响应头 X-Vertices/X-Triangles/X-Frame-Count/X-Fusion-Ms。

端→云上传契约(端侧打包 bundle)、NATS scan.captured/fusion_done、入队、GLB 存 MinIO 在 Go 侧(fusionworker)。
纹理烘焙/UV atlas(当前 GLB 仅顶点色)属 M3.14 后续。
"""
from __future__ import annotations

import os
import time

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response

from fusion_core import (FusionConfig, bake_albedo, fuse, fuse_with_poses,
                         mesh_stats, mesh_to_glb, textured_mesh_to_glb)
from rgbd_bundle import unpack

app = FastAPI(title="gomob-fusion-service", version="0.1.0")


@app.get("/healthz")
def healthz():
    deps = {}
    for name in ("open3d", "trimesh", "numpy", "PIL"):
        try:
            mod = __import__(name)
            deps[name] = getattr(mod, "__version__", "ok")
        except Exception:
            deps[name] = ""
    ok = bool(deps["open3d"]) and bool(deps["trimesh"])
    return {"status": "ok" if ok else "degraded", "deps": deps}


@app.post("/fuse")
async def fuse_endpoint(
    bundle: UploadFile = File(...),
    conf_threshold: int = Form(80),
    enable_confidence: bool = Form(True),
    voxel_size_mm: float = Form(6.0),
    mask_erode_px: int = Form(0),
    texture: bool = Form(False),
    tex_size: int = Form(1024),
):
    data = await bundle.read()
    try:
        frames = unpack(data)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"bundle 解包失败: {e}")
    if len(frames) < 2:
        raise HTTPException(status_code=400, detail="bundle 至少需 2 帧")
    try:
        cfg = FusionConfig(enable_confidence=enable_confidence, conf_threshold=conf_threshold,
                           voxel_size_mm=voxel_size_mm, mask_erode_px=mask_erode_px)
    except ValueError as e:   # __post_init__ 校验(如 mask_erode_px<0)
        raise HTTPException(status_code=400, detail=str(e))
    t0 = time.perf_counter()
    try:
        if texture:
            mesh, poses = fuse_with_poses(frames, cfg)
            tm, albedo = bake_albedo(mesh, frames, poses, tex_size=tex_size)
            glb = textured_mesh_to_glb(tm, albedo)
        else:
            mesh = fuse(frames, cfg)
            glb = mesh_to_glb(mesh)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=f"融合/导出失败: {e}")
    ms = int((time.perf_counter() - t0) * 1000)
    st = mesh_stats(mesh)
    headers = {
        "X-Vertices": str(st["vertices"]),
        "X-Triangles": str(st["triangles"]),
        "X-Frame-Count": str(len(frames)),
        "X-Fusion-Ms": str(ms),
        "X-Textured": "1" if texture else "0",
    }
    return Response(content=glb, media_type="model/gltf-binary", headers=headers)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("GOMOB_FUSION_PORT", "18092")))
