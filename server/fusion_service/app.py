"""fusion_service — 多视角 RGBD 云端融合 HTTP 服务(Open3D)。

形态对齐 asr_service(Python FastAPI 重计算服务):Go 侧 gomob-fusionworker 经 NATS 拿任务、
从 MinIO 拉 RGBD 帧包,POST 到本服务跑 Open3D 融合,拿回 mesh/GLB。算法在 fusion_core。

⚠ M3.14 首增量只落"算法核 + harness"(见 tests/harness/scan_fusion)。本 app 仅骨架:
  /healthz 就绪探针 + /fuse 端点轮廓(请求/响应契约待与 fusionworker 协商 RgbdShot 序列化后定稿)。
  端→云上传契约(RgbdShot + confidenceMap)、NATS scan.captured/fusion_done、GLB 导出/纹理烘焙
  属后续增量(TODO M3.14)。
"""
from __future__ import annotations

import os

from fastapi import FastAPI

app = FastAPI(title="gomob-fusion-service", version="0.1.0")


@app.get("/healthz")
def healthz():
    try:
        import open3d  # noqa: F401
        o3d_ok = True
        o3d_ver = open3d.__version__
    except Exception:
        o3d_ok, o3d_ver = False, ""
    return {"status": "ok" if o3d_ok else "degraded", "open3d": o3d_ver}


# TODO(M3.14 后续增量):/fuse 端点。
#   入:multipart RGBD 帧包(color jpg + depth16 + conf uint8 + 内参)或 MinIO 对象键列表。
#   出:GLB(mesh + PBR 纹理)+ stats(顶点/面/chamfer 自评/每帧采用率)。
#   现在先让 fusionworker 直接 import fusion_core 或本服务上线后再接 HTTP;
#   算法已在 fusion_core.fuse() 就绪并经 harness 验证。


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("GOMOB_FUSION_PORT", "18092")))
