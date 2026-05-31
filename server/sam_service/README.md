# sam_service — HQ-SAM 高精度分割服务(M3.17)

服务端收「RGB + 人工框/点」→ 出高精度 2D mask。形态对标 `fusion_service` / `asr_service`
(Python FastAPI 重计算服务,GPU 推理)。算法在 `sam_core.py`,HTTP 壳在 `app.py`。

**模型**:HQ-SAM(sam-hq,Apache-2.0,可商用),默认 `vit_h`。在原版 SAM 上加 High-Quality 输出 token
+ 融合早/末期 ViT 特征 → 边界更锐(细结构/薄边),保留 box/point 可提示接口。

**M3.17 当前增量边界**:只做「框/点 → mask」。框由**人工**给(不做自动 grounding);
mask 投到 depth、与启发式 ROI 的 A/B、端侧轻量化(MobileSAM/SAM2)列后续。

## 建环境

```bash
python3.11 -m venv .dev/sam-venv
# torch 必须用 CUDA 轮子(PyPI 默认可能是 CPU 版):
.dev/sam-venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
.dev/sam-venv/bin/pip install -r server/sam_service/requirements.txt
# 权重(~2.5GB,不进 git,见 .gitignore):
aria2c -x16 -c -d server/sam_service/weights -o sam_hq_vit_h.pth \
  https://huggingface.co/lkeab/hq-sam/resolve/main/sam_hq_vit_h.pth
```

## 跑

```bash
GOMOB_SAM_PORT=18093 .dev/sam-venv/bin/python server/sam_service/app.py
```

环境变量:`GOMOB_SAM_MODEL`(默认 vit_h;可 vit_l/vit_b/vit_tiny)、`GOMOB_SAM_CKPT`(默认 weights/sam_hq_<type>.pth)、`GOMOB_SAM_PORT`(默认 18093)。

## /segment 契约

- **入**:multipart;`image`=PNG/JPG 文件;form:
  - `box`="x0,y0,x1,y1"(像素,可选)
  - `points`="x,y;x,y"(可选)、`labels`="1,0"(可选,默认全 1=前景)
  - box 与 points 至少给一个;**box 单 mask 无歧义**(对应人工框选),纯点用多 mask 取最高分缓解粒度歧义。
- **出**:body=mask PNG(单通道 L,0 背景 / 255 前景);响应头 `X-Score`(模型 IoU 置信)、`X-Area`、`X-Width`、`X-Height`。

```bash
curl -s -X POST localhost:18093/segment \
  -F image=@scene.png -F box="120,80,520,420" -o mask.png -D -
```

## 质量验证

`tests/harness/sam_segmentation/`(合成星形场景 → 框 → IoU ≥ 0.92)。

## 许可与完整性

- **HQ-SAM(sam-hq)**:Apache-2.0;权重 `sam_hq_vit_h.pth` 由 Meta SAM(Apache-2.0)骨干微调而来 → **可商用**。
- **timm**:Apache-2.0;本服务只用 vit_h(不用 TinyViT 变体),不涉及 timm 部分 CC-BY-NC 预训练权重。
- **权重完整性**:`sam_hq_vit_h.pth` 期望 size=2570940653、
  sha256=`a7ac14a085326d9fa6199c8c698c4f0e7280afdbb974d2c4660ec60877b45e35`(HF lkeab/hq-sam)。
  `sam_core` 加载即查大小(瞬时);设 `GOMOB_SAM_VERIFY_SHA=1` 加查完整 sha256;下载时建议
  `aria2c --checksum=sha-256=<上述>` 校验在途完整性(segment-anything-hq 内部用 torch.load 反序列化 pickle,
  权重来源必须可信)。

## 部署须知(GPU / 鉴权)

- **GPU**:ViT-H 推理需 ~6-8GB 显存。生产由编排器显式设 `CUDA_VISIBLE_DEVICES` 分卡;
  `python app.py` 直跑时若未设会自动挑显存最空的一张兜底。
- **鉴权/限流**:本服务定位**内部服务**(对标 fusion_service/asr_service,Go 网关层做鉴权/限流),
  自身只对输入分辨率设上限(`MAX_PIXELS`)防超大图 OOM,不做 auth;**勿直接暴露公网**。
