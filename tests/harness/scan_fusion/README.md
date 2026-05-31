# scan_fusion — M3.14 云端多视角 RGBD 融合算法核 harness

## 目的

验证 04b 主线云端融合(M3.14 `object_3d_fusion`)的**算法核**:多视角 RGBD → 完整 mesh,
几何达标(chamfer ≤ 5mm)且**承接端侧 M1.6.20 的 conf 加权收益**。

M3.14 首增量只做"算法核 + harness 先行"(de-risk 最大未知:Open3D 多视角配准+PGO+conf 加权能否达标),
不碰 NATS/MinIO/DB/上传契约管线(后续增量,见 TODO M3.14)。

## 算法(`server/fusion_service/fusion_core.py`)

per-view 点云 → **全连接 multiway**:注册所有 i<j 对(相邻=里程计确定边,非相邻=闭环不确定边)
→ 全局 PoseGraph 优化(line process 自动下调坏边)→ conf 加权 TSDF 积分(`ScalableTSDFVolume`)→ Marching Cubes。
其中 pairwise=FPFH+RANSAC 粗对齐 → Color-ICP 多尺度精修(在下采样云上,FPFH 每云只算一次)。
`o3d.utility.random.seed(0)` 定 RANSAC/采样随机。

> 为何全连接:纯顺序链(只连相邻+单闭环)对单条 pairwise 失败极脆弱——实测一处误配翻 141°/741mm
> 就让下游所有视角崩坏且唯一闭环边被 prune 无法救。全连接给冗余约束,坏边可被绕过。

**置信加权落 Open3D 的形态**:Open3D Python 的 RGBDImage→PointCloud 与 TSDF.integrate **无 per-point/
per-pixel 权重 API**。故把端侧(M1.6.20)的"软加权"落成 Open3D 可行等价——**按 conf 阈值预掩码深度**
(conf<thr 像素置 0,不参与点云/配准/积分)。这正是已验证的 mask_recovery 形态(conf≥阈值保留干净像素),
且配准与积分用同一份 conf-masked 深度,位姿与体素来自同一可信像素集。真"软加权"需自写 C++ TSDF 扩展,
列后续;当前硬阈值已兑现 density-first+置信 收益。

## 跑法

```bash
# 需带 open3d 的 Python(首次建环境):
python3.11 -m venv .dev/fusion-venv
.dev/fusion-venv/bin/pip install -r server/fusion_service/requirements.txt
bash tests/harness/scan_fusion/run.sh
```

## 判定门

合成非对称带色 GT 物体(box+sphere+nub,~0.2m)→ 10 视角单环环绕 raycast 出 dense RGBD(`synth_dataset.py`)。
融合 mesh 在重建世界系(=cam0 帧),用已知 `inv(GT→cam0 外参)` 变回 GT 帧,与**观测面参考点云**比对称 chamfer。

**观测面参考** = 各视角 clean 深度反投影到 GT 系的并集(`synth_dataset.observed_surface`),即传感器真正看到的 GT 面。
单环赤道轨道看不到物体底面,用它作参考可排除不可观测区,使门① chamfer 度量**纯重建精度**而非不可达覆盖惩罚。

1. **① 干净输入 chamfer ≤ 5mm** — 算法几何本身达标(全连接 multiway 配准+PGO+TSDF 正确)。实测 ~1.3mm。
2. **② 带噪输入(40% 弱回波+飞点)conf 加权 chamfer ≤ 不加权** — conf 阈值预掩码兑现端侧加权收益到云端。
   实测 conf-on ~1.6mm vs conf-off ~35mm(降 ~96%)。

`fusion_bench.py` 产出两门 + 判定(正常/警告/异常)。实测三跑 clean 1.25–1.37mm、conf-on 1.61–1.66mm,verdict 稳定。

## 边界(诚实)

- 合成 RGBD 由 GT mesh raycast,无真实传感器畸变/多径;真实 8 张 P100R3 RGBD 端到端是后续增量(需上传契约 + MinIO 拉取)。
- conf 阈值预掩码是 Open3D-API 约束下的加权等价,非逐点软加权;弱区收益与端侧 mask_recovery 同源。
- chamfer 度量**观测面**精度:单环轨道不可见的底面不计入(GT 位姿对照实测 d_fg=1.3mm 与估计位姿一致,
  证配准已逼近真值、余误纯为底面覆盖伪影)。多仰角全覆盖会把视角裂成顶/底两簇致配准崩坏,已弃。
- **非完全确定**:RANSAC 含 OpenMP 并行,跑间有 ~±0.1mm 抖动(已设全局 seed 仍有线程序差异);
  但门判定裕度极大(门① ~1.3≪5mm、门② conf-on ~1.6≪conf-off ~35mm),verdict 跨跑稳定。
