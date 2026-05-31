"""sam_service — HQ-SAM 高精度分割 HTTP 服务。

形态对标 fusion_service / asr_service(Python FastAPI 重计算服务)。算法在 sam_core。
M3.17 当前增量:服务端收「RGB + 人工框/点」→ 出高精度 2D mask;投到 depth、A/B、自动 grounding 列后续。

/segment 契约:
  入:multipart,file 字段 `image`=PNG/JPG;form:
     box="x0,y0,x1,y1"(可选,像素整数);points="x,y;x,y"(可选);labels="1,0"(可选,默认全 1=前景);
     box 与 points 至少给一个。
  出:body=mask PNG(单通道 L,0=背景 255=前景);score/area/尺寸走响应头 X-Score/X-Area/X-Width/X-Height。
"""
from __future__ import annotations

import io
import os

import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from PIL import Image, ImageOps

from sam_core import deps_status, segment

app = FastAPI(title="gomob-sam-service", version="0.1.0")

# 输入分辨率上限(像素):SAM 内部把长边缩到 1024,过大输入只白耗显存/带宽,且防超大图 OOM/DoS。
# 本服务定位内部服务(对标 fusion_service/asr_service,鉴权/限流在 Go 网关层),不在此做 auth。
MAX_PIXELS = 4096 * 4096


@app.get("/healthz")
def healthz():
    d = deps_status()
    # CUDA 不可用也算 degraded:ViT-H 落 CPU 推理会慢几个数量级(分钟级),须告警而非静默慢。
    ok = (bool(d.get("torch")) and bool(d.get("segment_anything_hq"))
          and d.get("checkpoint_present") and d.get("cuda"))
    return {"status": "ok" if ok else "degraded", "deps": d}


def _parse_box(s: str):
    s = (s or "").strip()
    if not s:
        return None
    parts = [float(x) for x in s.replace(";", ",").split(",") if x.strip() != ""]
    if len(parts) != 4:
        raise HTTPException(status_code=400, detail="box 须为 'x0,y0,x1,y1' 四个数")
    x0, y0, x1, y1 = parts
    if not (x0 < x1 and y0 < y1):
        raise HTTPException(status_code=400, detail="box 须满足 x0<x1 且 y0<y1")
    if any(v < 0 for v in parts):
        raise HTTPException(status_code=400, detail="box 坐标须非负")
    return parts


def _parse_points(s: str):
    s = (s or "").strip()
    if not s:
        return None
    pts = []
    for pair in s.split(";"):
        pair = pair.strip()
        if not pair:
            continue
        xy = [float(x) for x in pair.split(",")]
        if len(xy) != 2:
            raise HTTPException(status_code=400, detail="points 须为 'x,y;x,y' 形式")
        pts.append(xy)
    return pts or None


def _parse_labels(s: str, n: int):
    s = (s or "").strip()
    if not s:
        return None
    labels = [int(float(x)) for x in s.replace(";", ",").split(",") if x.strip() != ""]
    if len(labels) != n:
        raise HTTPException(status_code=400, detail=f"labels 数 {len(labels)} 与 points 数 {n} 不符")
    return labels


@app.post("/segment")
async def segment_endpoint(
    image: UploadFile = File(...),
    box: str = Form(""),
    points: str = Form(""),
    labels: str = Form(""),
):
    raw = await image.read()
    try:
        img = Image.open(io.BytesIO(raw))
        img = ImageOps.exif_transpose(img)   # 应用 EXIF 朝向(手机相机常带),否则分割方向错
        img = img.convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"图像解码失败: {e}")
    rgb = np.asarray(img, dtype=np.uint8)
    h, w = rgb.shape[:2]
    if h * w > MAX_PIXELS:
        raise HTTPException(status_code=413, detail=f"图像过大 {w}x{h}(上限 {MAX_PIXELS} 像素)")

    bx = _parse_box(box)
    pts = _parse_points(points)
    pl = _parse_labels(labels, len(pts)) if pts else None
    if bx is None and pts is None:
        raise HTTPException(status_code=400, detail="须至少提供 box 或 points")
    if bx is not None and (bx[2] > w or bx[3] > h):
        raise HTTPException(status_code=400, detail=f"box 超出图像范围 {w}x{h}")
    if pts is not None:
        for px, py in pts:
            if not (0 <= px < w and 0 <= py < h):
                raise HTTPException(status_code=400, detail=f"point ({px},{py}) 超出图像范围 {w}x{h}")

    try:
        res = segment(rgb, box=bx, points=pts, point_labels=pl)
    except FileNotFoundError as e:
        raise HTTPException(status_code=503, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except RuntimeError as e:   # 含 CUDA OOM 等:优雅 503 而非进程崩溃/500 带栈
        raise HTTPException(status_code=503, detail=f"分割运行失败(可能显存不足): {e}")

    mask_png = Image.fromarray((res.mask.astype(np.uint8) * 255), mode="L")
    buf = io.BytesIO()
    mask_png.save(buf, format="PNG")
    headers = {
        "X-Score": f"{res.score:.4f}",
        "X-Area": str(res.area),
        "X-Width": str(rgb.shape[1]),
        "X-Height": str(rgb.shape[0]),
    }
    return Response(content=buf.getvalue(), media_type="image/png", headers=headers)


def _autopick_gpu():
    """未指定 CUDA_VISIBLE_DEVICES 时挑显存最空的卡(ViT-H 需 ~6-8GB,避免落到被占用的 cuda:0 OOM)。
    生产多卡调度通常由编排器显式设 CUDA_VISIBLE_DEVICES;此处仅为单机/dev 兜底。"""
    if os.environ.get("CUDA_VISIBLE_DEVICES"):
        return
    import shutil
    import subprocess
    if not shutil.which("nvidia-smi"):
        return
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=index,memory.free",
                              "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=5)
        rows = [r.split(",") for r in out.stdout.strip().splitlines() if r.strip()]
        best = max(rows, key=lambda r: int(r[1]))
        os.environ["CUDA_VISIBLE_DEVICES"] = best[0].strip()
    except Exception:
        pass


if __name__ == "__main__":
    import uvicorn
    _autopick_gpu()
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("GOMOB_SAM_PORT", "18093")))
