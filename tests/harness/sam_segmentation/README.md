# sam_segmentation — HQ-SAM 高精度分割质量评估(M3.17)

验证 `sam_service`/`sam_core` 的核心行为:**给一个(人工)框,能不能出高保真 mask**。

自包含合成场景(纯 numpy/PIL,不依赖 open3d,留在 sam-venv 内):
- 背景一种正弦纹理 RGB、前景一个 **9 尖星形**(细尖考验边界保真)填另一种更细纹理 + 轻传感器噪;
- GT mask = 星形多边形(精确已知,代「人工标注」);box = 其外扩 8px bbox(模拟人工松框)。

跑 `sam_core.segment(image, box)` → 与 GT 算 **IoU**。

## 跑

```bash
./dev.sh harness sam_segmentation
# 或:tests/harness/sam_segmentation/run.sh
```

需带 torch(CUDA)+ segment-anything-hq 的 venv(默认 `.dev/sam-venv`)+ 权重 `server/sam_service/weights/sam_hq_vit_h.pth`。
建环境/下权重见 `server/sam_service/README.md`。`run.sh` 采样写 `.dev/sam_segmentation/`(scene/pred/gt png + metrics.json),
`analyze.py` 判定(`./dev.sh harness` 自动调)。

## 门

| 指标 | 含义 | 门 |
|------|------|----|
| IoU | pred mask vs GT 星形,交并比 | **硬** ≥ 0.92(对齐 TODO M3.17) |

星形细尖正是原版 SAM 容易糊、HQ-SAM 高质量 token 发力之处;0.92 门要求边界基本贴合而非只「框住大概」。

产物 `scene.png`/`pred_mask.png`/`gt_mask.png` 落 `.dev/` 供人工复核。

## 边界(后续)

- 当前合成场景测「框→高保真 mask」基本面;真实图像(纹理/遮挡/弱对比)基准、与 M3.14 启发式 ROI 的 A/B、
  mask 投 depth 后的 mesh 边缘毛刺对比,列 M3.17 后续增量(见 04b §4 / TODO)。
