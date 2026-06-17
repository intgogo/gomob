# 几何车辆部件测量：轴距/前后悬(贴地接触带) + 货箱(高度剖面+rim+恒宽段)，无 DL

## What

激光融合点云上做**轴距/前后悬**(`axle.go`)与**货箱外尺寸**(`cargobox.go`)测量的几何方法，已在原厂真值 `Data/100742` + 合成真值夯实并落 Go（harness `tests/harness/vehicle_axle/`、`vehicle_cargobox/`）。原厂用 PointSIFT DL，权重不可得 → 几何路径(物理可解释、PCL-free、无需训练数据)，与 JCHY 主路同构。

### 轴距/前后悬

**物理判据**：轮是车上**唯一触地的部件**。取贴地接触带（离地高 `< contactH`，默认车高 8%、下限 40mm）的点，沿 OBB 对齐后的**车长轴**做密度直方图 → 峰 = 轴心。车厢侧壁/底盘悬在离地间隙以上、**不进接触带**，自然被排除。这是当初失败的关键纠偏：用厚低带（z0+180/200）会把侧壁/底盘也纳进来，4 轴信号被淹没；薄接触带（z0+60）干净分出 4 峰。

- 轴距 = 相邻轴心间距；前/后悬 = 车体两端到首/末轴心的伸出量。
- 峰检测 `findPeaks1D`（高度阈 0.25×max + 突出度 0.20×max + 最小间距 150mm），峰心用接触密度加权细化。
- 车头端判定：邻接最大轴距且其外侧轴数更少的一端（单转向轴 vs 后轴组）。
- `MeasureFull` 同一车体点、同一 OBB 帧一遍出 LWH + 轴距，接入 runner `axle` stats + `FusionDoneEvent` + 网页测量面板（`/latest` 拍平历史扫描）。

### 货箱外尺寸（cargobox.go）

**物理判据**：货箱=车尾侧"顶高接近全局最高"的最长连续段（车头顶矮、且与箱间常有缝），沿车长 maxZ 剖面找。箱顶 **rim**(顶部薄层 z>top-7%车高)的长/宽=货箱外长/外宽（rim 干净、无轮干扰）。箱壁竖直 → 横截面宽度随高度**恒定**；最长"恒宽"z 段=箱壁，其**底=bed floor**，箱深=箱顶-bed（≈栏板深度）。内宽=壁带宽度直方图**最靠中心两壁峰**间距——但厚壁时直方图分辨率把内/外壁面合并成一峰，只给"壁中距"（薄壁≈内宽，厚壁介于内外之间）→ **参考值，不硬断言**。
- 验收：合成"车头+开顶货箱"已知尺寸闭环（外长1007/1000、外宽600/600、箱深454/460，3 旋转角证旋转无关）；`100742` 货箱占车长 59%、外长1057/外宽466/箱深455（与 rim/EDA 一致）。**100742 无货箱数值真值**（carType=2 未触发箱测，Result.ini hxInner 全 0）→ 数值闭环只能靠合成，真车箱尺寸待设备扫箱车验证。

### 网页可视叠加（overlay.go + web）

融合后把分割结果"看得见"：`BuildVehicleOverlay` 把 车体框/货箱框/各轴线 按**融合云世界系**导出(8 角点/两端点)，网页 `renderVehicleOverlay` 在融合视图 markerLayer 画 SVG(`projectPointInPane` 投影，渲染器无关，复用 renderRegionWall 范式)。**关键**：测量在变换帧 M(车位框/地面/设备系)里算，叠加须求 **M→世界仿射逆**——三帧统一成 `world=O+m·(A0,A1,A2)`(设备恒等、地面 `toGroundFrame⁻¹` O=-d·up、车位框 `toBoxFrame⁻¹` O=框心含偏移)，OBB 对齐帧角点 `m=l·Ldir+w·Wdir+h·ez` 再映回。车体框用 cleaned(主簇→ROR，与测量 OBB 一致)，轴/货箱用 roiPts(自带 trim)。盒角点验证用**棱长**(旋转无关)。测量面板融合后总是出现：测不出给原因(未标定/需圈车位框)，不静默隐藏。

## Why

JCHY 原厂轴心用 PointSIFT `wheel_seg` DL 模型取（`caluteDeepWheel`/`segWheelBottom`/`SortWheelYMin2fMax`），但**模型权重不可得**（`models/` 空、Windows GPU 算子、网络结构未知，见 [[finding_jchy_measurement_layer_re_2026-06-04]]）。用户拍板**几何结构优先**：贴地接触带是物理可解释、可量测复现、PCL-free（纯 Go）、无需训练数据的最优解，且与 JCHY 主路（PCL 聚类+OBB，DL 只补难分割目标）同构，非妥协。

## How to apply

- **关键陷阱（必记）**：轴心检测**不能跑在 `largestCluster` 之后**。悬挂间隙 > 体素(10mm)时，轮与车体在体素图里**不连通**，`largestCluster` 把它当独立小簇丢掉（尤其驾驶室下遮挡的前轮）——车体主簇仍在（LWH 不受影响、length 照样 1795），但该轴的贴地接触峰塌到检测阈值下 → 整条轴漏检。`measureBody` 因此返回**裁剪后/聚类前**的 `roiPts` 给 `DetectAxles`；接触带 + 峰突出度本就抗散点噪声。LWH 仍走 主簇→ROR（干净 OBB）。
- 验收基线：`./dev.sh harness vehicle_axle` 或 `go test ./internal/laser -run TestAxle`。`100742`（常规货车模型，4 轴=前1+后三联）→ 轴距 744/404/263 vs 真值 710/399/261、前悬250/后悬163；合成 4 轴 31°旋转下 700/400/300 精确（证旋转无关）。
- **后悬最弱（~18%）** 受尾端噪声 + 尾轮挡泥影响；`contactFrac`/峰阈值默认在比例模型上调，**真车需按设备扫描重标**（真车通常只有轮触地、无近地侧壁，信号更干净）。
- 原厂真值只有 **1 笔会话**（100742，无货箱/罐体/栏板），货箱/罐体测量无法对真值闭环，须靠真机扫箱车/罐车验证。

## 相关

- [[finding_jchy_measurement_layer_re_2026-06-04]] — JCHY 测量层逆向（8 阶段管线、车型表、PointSIFT 不可得）
- `docs/architecture/16-jchy-vehicle-measurement-app.md` §3⑥/§5/§9/§10 — 测量管线 + 缺口
- `server/internal/laser/{axle.go,cargobox.go,measure.go}`、`tests/harness/{vehicle_axle,vehicle_cargobox}/`、`TODO.md` M9.4/M9.4b
- EDA 产物 `.dev/vehicle-parts/`（侧视/俯视/接触带渲染、真值轴心叠加）
