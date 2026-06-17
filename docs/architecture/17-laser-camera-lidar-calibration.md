# 17 · 激光站相机↔雷达标定 — 原理学习文档

> **定位**：学习向技术文档。讲清 LTS-T1 双单元激光站「相机+雷达」标定的**原理、靶的设计、数学、流水线**，
> 以及它怎么落到本仓的上色/融合链路。
> **不是**操作手册（厂商标定在 Windows 工具里跑），而是帮你**看懂这套标定为什么这么设计**。
>
> 真理源：**外部逆向目录** `/root/lilw/lidar/re/`（逆向自厂商 `LIDAR_PTZ.exe`，含 `spec_calibration.md` 等，
> 体量大未迁入仓）、本仓在树实现 `server/native/lidar/src/{calib,texture,config}/`、设备实拉 `calib_10x.json`。
> 关联：[15-laser-scanner-integration](15-laser-scanner-integration.md)、
> 记忆 `docs/agent-memory/finding_laser_a_station_texture_2026-06-15.md`。

---

## 0. 读这份文档你会得到什么

- 知道「**相机靶**」「**雷达靶**」分别是什么、为什么必须是这两种形态（不是随便一张棋盘纸）。
- 看懂标定在解什么：从 `tag_pos.csv` / 平面墙 → Ceres 优化 → `calib_10x.json` 里那堆四元数+内参。
- 理解**世界→相机**的完整变换链（9 步），并认出它就是本仓 `colorizer.cpp` 上色时用的同一套公式。
- 判断**什么时候需要重标、怎么验证**，以及本仓 Linux 端**能跑到哪一步、缺什么**。

---

## 1. 先问：为什么需要标定？（从目标倒推）

我们要做的事：把**相机拍到的颜色**贴到**雷达扫到的 3D 点**上（就是激光页的「上色 / texture」）。
要做到这点，对每个雷达 3D 点 `P`，必须能算出「它在相机图像里落在哪个像素 `(u,v)`」，去那个像素取颜色。

```
雷达点 P (3D, 世界系) ──[世界→相机变换链]──▶ 相机像素 (u,v) ──取色──▶ 给 P 上色
```

这条「世界→相机」的变换链由一堆参数决定：相机**内参**(fx,fy,cx,cy)、**畸变**(k1,k2,p1,p2,k3)、
相机相对转轴的**外参**(旋转+平移)、雷达相对转轴的外参、设备相对世界的位姿。
**标定 = 把这些参数解出来。** 解不准，颜色就贴歪（车漆溢到背景、纹理整体平移）。

> 这条链的代码就在本仓：`server/native/lidar/src/texture/colorizer.cpp` 的 `worldToCamera` + `projectToPixel`。
> **标定是「拟合这条链的参数」，上色是「用拟合好的参数跑这条链」——同一个前向模型,两个用途。**

---

## 2. 核心直觉：两种传感器 → 两种靶

这是整套标定设计的**第一性根因**。相机和雷达测量世界的方式不同，所以标定靶的形态必须不同。

| | **相机** | **雷达 (LIDAR)** |
|---|---|---|
| 测量本质 | **测角**：一个像素 = 一条射线/方向，**没有深度** | **测距**：直接给出 3D 点坐标 |
| 它"不知道"的 | 这条射线上到底是哪个点 | 这一束打中了面上的哪个**具体位置** |
| 标定要补的约束 | **点对应**：已知 3D 点 ↔ 实测 2D 像素 | **几何约束**：点必须落在已知**平面**上 |
| 靶的形态 | **编码标记场**（自带身份的 3D 点阵） | **平面墙**（方程已知的大平面） |
| 残差 | 重投影误差 `(u_pred−u_obs, v_pred−v_obs)` | 点到平面距离 `n·p+d` |

> **一句话对偶：相机靶解决「对应」（点↔点），雷达靶解决「约束」（点↔面）。**
> 相机需要知道"这个像素=哪个 3D 点"，所以靶必须**自带身份**（编码）；
> 雷达已经有 3D 坐标，只需知道"这点在某个已知面上"，所以靶用**平面**最自然。

下面分别展开。

---

## 3. 坐标系与变换链（公共骨架）

这台是**云台 (PTZ)**：相机和雷达装在会转的转轴上，扫描时绕轴 pan。所以标定的是各传感器
**相对转轴的固定外参**，成像时再叠加**每帧的航向角 θ**（当前转到哪了）。帧链：

```
世界系 world
   │  device→world 位姿 (q_dev, t_dev)          ← 设备摆在标定场的哪、朝哪
设备系 device
   │  每帧航向角 Rz(θ)                            ← 云台转到的角度（逐帧变）
转轴系 axis
   │  fixed_transform (config 里的名义安装 4×4)   ← 转轴→传感器安装座，机械设计常量
   │  + 修正外参 (corr_quat, corr_offset)         ← 真机微调量，标定优化出来
相机光心系 / 雷达系
```

- `fixed_transform`：**名义安装**，写死在 `config.yaml`（如相机 `camera_calibration.fixed_transform`），
  标定不动它。它承载了"120° 名义安装"这种机械约定。
- `corr_quat / corr_offset`：**真机精修量**（常是 1~2° 级别的小修正），**标定真正优化的就是这个 delta**。
- 解出来后两者合成 = `*_rot_quat`（headline 外参，写进 JSON）。

> 看 `calib_102.json`：`camera_rot_quat=[0.5,0.5,-0.5,-0.5]`（名义安装），
> `camera_corr_quat≈[0.9997,-0.017,0.014,0.009]`（≈2° 精修）——正好对应上面两层。

---

## 4. 相机靶详解（ReprojectionFunctor）

### 4.1 靶是什么

**一个铺满标定间的编码标记场**：
- **~490 个编码标记**（ArUco / 很可能 AprilTag-36h11 字典），贴在四周墙面/支架上。
- 每个标记的 **3D 世界坐标都测量过**（全站仪/卷尺），记在 `tag_pos.csv`：
  ```
  descrip,x,y,z
  100011001010001100011111010010011001,1.04828,2.70671,0.459755
  ...
  ```
  `descrip` = 该标记的 **36 位编码**（'0'/'1' 串，就是它的身份）；`x,y,z` = 世界坐标（米）。
- 样本里 **490 个标记，坐标跨度约 5m×10m×2.5m**——即整个标定间。

**为什么要编码 + 测量坐标？**
- **编码（身份）** 解决相机标定的核心难点——**对应问题**：一个像素可能对应射线上任何点，
  你怎么知道"图里这个白块 = 房间里 (1.05, 2.71, 0.46) 那个标记"？编码标记一检测就报出唯一 ID，
  自动配上它在 `tag_pos.csv` 里的真值坐标。普通圆点/纯色块**没有身份**，配不上，所以不能用。
- **测量坐标（真值）**：这些 3D 点是拟合相机模型的"地面真相"，坐标不准 → 标定不准。
- **要 490 个、铺满空间**：待解参数多（内参 4 + 畸变 5 + 外参 7），必须在整个视场、各距离/角度
  都有约束，才解得稳、不过拟合。

### 4.2 怎么采集

云台带着相机**转一圈**，多帧拍下这些标记。每帧：
- OpenCV ArUco `detectMarkers` 检出标记 → 给出角点像素 `(u_obs, v_obs)` + 编码 ID。
- 该帧的**航向角 θ** 从**图像文件名** `..._<angle>.<ext>` 解析（逐帧常量）。
- 按编码 ID 把检出的标记匹配到 `tag_pos.csv` 的 3D 坐标 `Pw`。
- 每个成功匹配的 `(标记, 帧)` → 一条**重投影残差**。样本一次跑出 134 条匹配。

### 4.3 数学：世界→相机 9 步前向模型

给定一个标记 3D 点 `Pw` 和当前参数，预测它在图像里的像素，再和实测像素求残差。
（步骤名取自厂商 `DebugReprojection` 的打印标签；UNCERTAIN 项见 §8。）

```
1. Pw_world      : 标记世界坐标 (tag_pos.csv，或被 ICP 精修)
2. → 设备系       : Pw_dev = R(q_dev)⁻¹ · (Pw − t_dev)
3. → 转轴系       : Pw_ax  = Rz(±θ) · Pw_dev            (±θ 符号 UNCERTAIN, U3)
4. → 相机安装座   : Prx    = T_fix^cam · Pw_ax           (config 的 4×4 名义安装)
5. → 相机光心     : Pc     = R(q_cam)⁻¹ · (Prx − t_cam)  (先减 t_cam 再转,已确证)
6. 透视除         : xn = Xc/Zc,  yn = Yc/Zc
7. Brown-Conrady : r²=xn²+yn²
                   radial = 1 + k1·r² + k2·r⁴ + k3·r⁶
                   x_d = xn·radial + 2p1·xn·yn + p2·(r²+2xn²)
                   y_d = yn·radial + p1·(r²+2yn²) + 2p2·xn·yn
8. 针孔成像       : u_pred = fx·x_d + cx,  v_pred = fy·y_d + cy
9. 残差           : ( u_pred − u_obs ,  v_pred − v_obs )    ← 2 维
```

合成式：
```
Pc = R(q_cam)⁻¹ · ( T_fix^cam · Rz(±θ) · R(q_dev)⁻¹ · (Pw − t_dev) − t_cam )
```

> **这就是本仓 `colorizer.cpp::worldToCamera` + `projectToPixel`。** 上色时用**标定解出来的**
> 这些参数，把每个雷达点跑一遍 1→8，得到像素去取色（step 9 残差只在标定时用）。
> 看代码：`server/native/lidar/src/texture/colorizer.cpp`。

### 4.4 解什么

Ceres 用 `AutoDiffCostFunction<ReprojectionFunctor, 2, 3, 4, 4, 5>`，**优化 4 个参数块**：
`cam_t[3]`、`cam_q[4]`、`intrinsics[4]`、`distortion[5]`。
目标：所有 `(标记,帧)` 的重投影残差平方和最小 → 解出相机内参+畸变+相机↔轴外参。
（标记 3D 坐标、观测像素、每帧航向、device→world 位姿、fixed_transform 都是**常量**，不优化。）

---

## 5. 雷达靶详解（P2PlaneFunctor）

### 5.1 靶是什么

**几面方程已知的平整墙**。雷达控制不了激光束打中墙上的哪个点，没法做"点对应"，
所以换约束：**让点落在已知平面上**。平面写在 `config.yaml: lidar_calibration.planes`：

```yaml
planes:
  # 格式 [a, b, c, cx, cy, cz, (l, w, h?)]，ax+by+cz+d=0，(cx,cy,cz) 是平面上一点
  - [-0.000478, -0.000574, 1, -1.5215, -0.8530, -0.995, 10.893, 9.428, 0.03]
```
- `[0..2] = a,b,c` = 平面**法向** `n`；`[3..5] = cx,cy,cz` = 平面上一点 → `d = −n·(cx,cy,cz)`。
- `[6..8]` = 三个额外量，**UNCERTAIN**（很可能是面片包围盒 `l,w,h`，用来把点限制在**有限**面片内，U2）。

**为什么用平面、不用编码标记？** 雷达已经有 3D 坐标，**不需要身份**——只要这点"在某个已知面上"
就够约束。平面又大又好造（一堵平墙）、方程好测，是雷达最自然的靶。

### 5.2 数学：点到平面残差

```
p_world = R(q_dev) · ( Rz(h_offset) · T_fix^lid · ( R(q_lid)·p_lidar + t_lid ) ) + t_dev
residual[0] = n · p_world + d           ← 有符号点到平面距离，理想为 0
residual[1] = (第 2 约束，UNCERTAIN U1)  ← 残差维=2，第二维含义未定
```

### 5.3 解什么 + ICP 迭代

`AutoDiffCostFunction<P2PlaneFunctor, 2, 3, 4, 3, 4, 1>`，**优化 5 个参数块**：
`dev_t[3], dev_q[4], lid_t[3], lid_q[4], h_offset[1]`。外层套一个 ICP：

```
重复（icp_max_iterations=5）:
  1. 每个激光点 → 最近平面；点到面距离 < plane_max_distance(0.2m) 才接受为对应
  2. 用所有对应建 Ceres Problem，每对一条 P2Plane 残差
  3. Solve；打印 Iter | Avg Res | dev q/t | lidar q/t | h_offset | dR | dT | Matches
  4. dR,dT 够小 → 提前收敛
```
解出雷达↔轴外参 + **设备↔世界位姿**。这个 `device→world` 随后**固定**，喂给相机阶段（§4 的 `q_dev,t_dev`）。

> 另有一个 **PCL ICP** 把"合成点云对齐到已知标记布局"，**精修 `tag_pos.csv` 的每个标记 3D 坐标**，
> 再喂回相机阶段 step 1。即标记坐标既靠测量、也靠点云 ICP 微调。

---

## 6. 完整标定流水线（6 阶段）

厂商 `runFullCalibration`，执行顺序按阶段号：

```
Stage 1  parseOnce              原始扫描 → 源点云 (origin_points.pcd)
Stage 1' setPlanePairs          载入平面、设设备初始位姿
Stage 2  optimizeAgainstPlanes  雷达↔轴 点到平面 ICP (§5) → 雷达↔轴 + 设备↔世界
Stage 3  optimizeCameraMultiFrame  相机↔轴 ArUco 重投影 (§4) + PCL ICP 精修标记坐标
Stage 4  synthesize             合成带强度点云（验证用，可关）
Stage 5  textureMap             纹理映射验证（textured_final.pcd，可关）
Stage 6  saveCalibration        写 YAML + JSON (§7)
```

注意**先雷达后相机**：雷达阶段先把"设备↔世界"位姿钉死，相机阶段才在这个固定底座上解相机参数。

**doAutoCalibration vs doManualCalibration**（同一套数学，只是数据来源不同）：
- Auto：设备**自动扫一圈**采集 → 自动链入流水线（失败可 retry）。
- Manual：对**已采好**的扫描+图像+`tag_pos.csv` 跑流水线，用户触发。

---

## 7. 标定输出（calib_10x.json schema）

Stage 6 写一份 JSON（被上色器读）+ 一份同数据 YAML。JSON：

```jsonc
{
  "parameters": {
    "lidar": {
      "lidar_rot_quat":   [w,x,y,z],   // 雷达→轴 旋转（headline）
      "lidar_corr_quat":  [w,x,y,z],   // 雷达精修四元数
      "lidar_corr_offset":[x,y,z]      // 雷达精修平移
    },
    "camera": {
      "camera_rot_quat":   [w,x,y,z],          // 相机→轴 旋转（headline）
      "camera_corr_quat":  [w,x,y,z],          // 相机精修四元数（≈2°）
      "camera_corr_offset":[x,y,z],            // 相机精修平移
      "camera_intrinsic":  [fx,fy,cx,cy],      // 内参
      "camera_distortion": [k1,k2,p1,p2,k3]    // 畸变（OpenCV 序）
    },
    "body2world": {                            // 设备→世界位姿（上色器不读，流水线内部用）
      "b2w_quat":[w,x,y,z], "b2w_offset":[x,y,z], "b2w_scale":<double>
    }
  }
}
```

- `rot_quat` = 合成的传感器→轴旋转（headline 外参）；`corr_*` = 在 config `fixed_transform` 之上
  Ceres 优化出的**小修正**。上色器**两者都读**（`fixed_transform` 出 `rot`、`corr` 出精修）。
- 这正是我们 `lidar_cli device calib <ip>` 从设备拉下来的 `calib_101.json` / `calib_102.json`。
- 喂给上色：`colorizer.cpp::CameraModel::fromConfig(config) + applyCalibration(calib)`——
  `fixed_transform`+`texture_mapping` 来自 config YAML，内参/畸变/corr/b2w 被 calib JSON 覆盖。
  详见 [finding_laser_a_station_texture](../agent-memory/finding_laser_a_station_texture_2026-06-15.md)。

---

## 8. 本仓现状：能跑到哪 / 缺什么

| 环节 | 状态 |
|---|---|
| Ceres 解算器（两个 functor） | ✅ 在树 `src/calib/calibration_pipeline.{h,cpp}`，合成数据单测可复现已知参数 |
| 单元**相机↔雷达**完整标定（490 标记场 + 平面墙，6 阶段） | ❌ **本仓没有端到端流水线**——靠厂商 Windows 工具 + 标定间。本仓只消费结果（`device calib` 拉 `calib_10x.json` 上色） |
| 完整标定工具 | 厂商 **Windows** `LIDAR_PTZ.exe` / `lts-tool.exe`（`/root/WindowsR/LIDAR_PTZ/`）+ 设备 `doAutoCalibration` |
| 标定结果存哪 | **设备内**（`device info` 显示 `calib=1`）；经 `/api/update_calib_parameters` 写回；我们 `device calib` 拉下来 |
| 站间融合外参 site（A↔B）—— ICP | ✅ `lidar_cli site-extrinsic`/`register`，不需打印靶，但要点云重叠+特征 |
| 站间融合外参 site（A↔B）—— **现场共享标记场（自标定）** | ✅ **2026-06-15 新增**：`lidar_cli calib-site-markers`，贴 ArUco 即可，见 §9.5 |

> **区分两件事**：① **单元相机↔雷达**的完整标定（内参+外参，490 标记场）是装站时"标定间"作业，本仓不重跑；
> ② **多单元 A↔B 拼接**（site 外参）是每个工位都要做的，本仓现在能做——ICP 或现场共享标记场（新）。

---

## 9. 实操：要不要重标 / 怎么验证

- **修了相机后要不要重标？** 若只是**原位重装、相机相对雷达没移位**，厂家标定（`calib=1`）仍成立。
  实测佐证：A 站修完相机，直接拉 `calib_101.json` 上色，**mapped 30.7%，与 B 的 30% 持平**。
- **mapped 率说明什么 / 不说明什么**：mapped = 投进相机视锥、取到色的点占比。
  ~30% 证明**相机指向/FOV 套合正确**（没有粗错），**但不证明亚厘米外参精度**。
- **怎么判断准不准 → 看颜色边缘对齐**：车漆颜色有没有溢到背景、纹理整体有没有平移/错位。
  没溢色就别动它；明显错位才考虑重标（厂商工具 + 标记场，或在 Linux 端补一套轻量标定）。
- **A↔B 拼不齐** 是另一回事（site 外参），ICP 重算 `site_extrinsic.json` 即可，与相机靶无关。

---

## 9.5 现场共享标记场：多单元 A↔B 自标定（2026-06-15 新增）

把**两个单元拼进同一个世界系**有两条路：ICP（靠点云重叠，§8）和**共享标记场**（本节，靠相机看公共标记）。
标记场的好处：不依赖点云重叠/特征、带绝对尺度、对称车型也稳。本仓实现的是**自标定**版——
现场贴 ArUco 即可，**无需测量靶坐标、无需特制板**。

### 原理（A 系=世界，solvePnP + Umeyama）

每个单元的相机内参/畸变/相机↔轴已知（设备 `calib_10x.json`）。现场贴**已知边长**的 ArUco
（`DICT_APRILTAG_36h11`）于重叠区，两单元各 pan 一圈拍图：

```
1. 检测：每张图 cv::aruco::detectMarkers → (id, 4 角点)
2. 单标记测姿：solvePnP(已知边长, 角点, K, dist) → 标记 6DoF 位姿(rvec,tvec)；用位姿×标记物点得
   【相机系】4 角点坐标(非仅中心，带标记朝向)
3. 投到单元自身系：cameraToWorld(每个角点, heading) → 角点在【该单元系】          ← 复用 colorizer 前向模型的逆
4. 跨帧聚合：同 id 多 heading 对每角点求均值 → markerCorners[id]（4 点/标记）
5. 公共标记 3D↔3D：两单元都重建到的 id 的 4 角点，给出对应点 → Eigen::umeyama(src=B, dst=A) → 刚体 B→A
```

关键洞察：**标记是共享的**——一个标记场同时被两单元观测，A 系定义世界，B 对着同一批标记定位，
`B→A` 就是两套 `markerCorners` 的刚体对齐。带已知边长的 solvePnP 单视角即出深度，比"靠 pan 圆轨道
小基线三角化"稳得多（2–5m 远距尤其）。

> **为什么用 4 角点而非仅中心点（2026-06-17 订正）**：旧实现只取标记中心(tvec)做 umeyama。现场只有
> ≤4 个、且大致**共面**（平铺地面/桌面）的标记时，少量中心点对旋转**欠约束**——RMS 看着小、外参却偏
> 十几到二十几度（真机实测融合错位 ~480mm，详见 `docs/agent-memory/finding_laser_site_marker_corner_pose_2026-06-17.md`）。
> 改用每标记 4 角点（带 solvePnP 朝向）后，**单个标记即完全约束 6DoF 旋转**，少量/共面标记也解得准
> （host 测试：2 共面标记复原 B→A 到机器精度）。`min_common` 随之从 4 放宽到 2。

### 在树实现

| 件 | 位置 |
|---|---|
| 核心 + 前端 | `src/calib/site_marker_calib.{h,cpp}`：CORE(`aggregateMarkerCorners`/`solveSiteExtrinsic`，纯 Eigen，按 4 角点) + FRONT-END(`detectUnitCenters`，OpenCV aruco+solvePnP，出角点相机系坐标) |
| CLI | `lidar_cli calib-site-markers <imgA> <cfgA> <calibA> <imgB> <cfgB> <calibB> <out_site.json> [len_m] [min_common]` |
| 输出 | `saveSiteExtrinsic` 写 B→A 4×4（米）→ **现有 `align=site` 融合直接吃** |
| harness | `tests/test_site_marker_calib.cpp`：合成场复原已知 B→A（精确 + σ=2mm 噪声）。实测：精确解机器精度；**噪声下 B→A 误差 0.9mm / RMS 1.6mm**（多帧多标记均值平滑） |

> 依赖：OpenCV **4.6**（带 `aruco` contrib）。注意环境里 `/usr/local` 有个 4.5.5（**无 aruco**）会被
> CMake 默认选中——构建脚本已 `-DOpenCV_DIR=/usr/lib64/cmake/OpenCV` 锁 4.6。

### 现场操作流程

1. **打印**：`DICT_APRILTAG_36h11`，**已知边长**（如 0.15m，要量准），贴 ≥6 个于两单元重叠视野
   （保证 ≥4 个公共可见；铺开、非共面）。
2. **采图**：每单元 pan 一圈采图像流（`ipX:4003`），导出按航向命名 `*_h<度>.jpg`
   （`lidar_cli device capture <ip> 4003 <秒> a.bin` → `lidar_cli replay img a.bin imgA/`）。
3. **解算**：`lidar_cli calib-site-markers imgA cfgA calibA imgB cfgB calibB site_extrinsic.json 0.15 4`
   → 打印 `ok/common/rms`，达标则写 `site_extrinsic.json`。
4. **用它**：融合走 `align=site` + 该 `site_extrinsic.json`（`scan_vehicle.cpp` 优先级 `site > icp > none`）。

### 边界 / 待办

- **前端坐标系约定**（solvePnP 的相机光心系 ↔ `CameraModel` 光心系，z 前/y 下）harness 未覆盖，
  **需真机图像验证一次**；core 几何/umeyama 已 harness 证明。
- 当前 v1 = 均值 + Umeyama（闭式、稳）。可选 **Ceres 联合 BA**（所有重投影一起优化 marker 位置 + B 位姿）
  进一步提精度——留 TODO。
- 无离群剔除；某标记误检会拉偏 Umeyama。看 `rms` 把关，必要时加 RANSAC。
- **一键现场标定**已落地两形态（均经 laserworker exec 独立 `lidar_cli`，解算器不进 cgo 精简库）：
  ① **离线一键**`POST /v1/scans/laser/site-calib`（两单元 sweep→存图→`calib-site-markers`→落 `site_extrinsic`）；
  ② **实时取景**`POST /v1/scans/laser/site-framing`（边扫边推 RGB 帧+检测，看着对标记，见 §9.6）。

---

## 9.6 实时取景标定：边扫边看 RGB 图 + ArUco 检测（2026-06-15 新增）

§9.5 的离线/一键流程**看不到相机拍到了什么**——点云里标记又小又稀，对不准。本节是 §9.5 的**实时取景**
版：一次可控扫掠，相机逐帧把 RGB + 检测框推到网页，操作员**直接看着相机图**确认标记被拍到、被认出，
扫完同一趟自动解算 A↔B。解决"点云看不清标记"的根本痛点。

### 硬件约束（实测，决定形态）

LTS-T1 相机 = IMX415 **3840×2160 高清静止相机**，`device_info` 报 **fps≈0.33**（约 3 秒一帧）。
**实测：设备 READY 静止停泊时连 4003 抓 4 秒 = 0 帧**——相机**只在云台转动时逐帧出图**，停下即停拍。
所以这台硬件**给不了真正的"实时视频"**；能给的最接近形态 = **可控扫一段 + 边扫边推帧的胶片预览**
（这也是用户拍板选的形态）。"云台控制"= 设扫描起止角 + 速度（单 pan 轴，无独立云台）。

### 链路（Go 薄代理 + lidar_cli 全责）

```
网页 POST /v1/scans/laser/site-framing?a_start&a_stop&b_start&b_stop&speed&marker_len
  → Go 设两单元扫描角/速度(云台控制) → exec `lidar_cli framing-stream`(被动连两单元 4003)
  → 见 READY 即 devctl SCAN_START 起扫 → lidar_cli 逐帧:解码→aruco 检测→solvePnP→预览降采样
  → stdout 二进制帧协议 → Go 读后转 NATS laser.frame(owner 路由) → signaling 桥 → 浏览器 /v1/ws
  → 网页渲染:画布画帧 + 绿框叠检测 + 胶片缩略图; 扫完 lidar_cli 聚合 umeyama 解 B→A → 'r' 回 HTTP
```

与 cgo 采集同范式：**lidar_cli 被动**（等设备进 SCAN），**Go 持设备门控**（设角/速度 + SCAN_START/STOP）。
解算器（aruco + Umeyama）仍只在 `lidar_cli`，**不进 cgo 精简库**（laserworker 保持无 Ceres/aruco）。

### 在树实现

| 件 | 位置 |
|---|---|
| C++ 取景流 | `src/calib/framing_stream.{h,cpp}`：被动双流 + 逐帧检测 + 二进制帧协议 + 扫完 umeyama；`lidar_cli framing-stream <ipA> <cfgA> <calibA> <ipB> <cfgB> <calibB> <out.json> [len_m] [min_common] [prevW]` |
| stdout 协议 | `[4B BE N][1B type][N payload]`；`'s'` 事件 JSON、`'m'` 帧 `[4B metaLen][meta][preview jpeg]`、`'r'` 结果 JSON。检测在**全分辨率**做，角点像素缩放回预览系供前端叠加 |
| Go 端点 | `server/internal/laser/siteframing.go`：`SiteFraming` + `readFramingRecords`/`decodeFrameRecord` + `applyFramingControl`(读改写设角/速度) + `newFramingGate`(纯 SCAN_START/STOP) |
| NATS 桥 | `signaling/laser_bridge.go` 加 `laser.frame` 主题 → 复用按 owner 路由的 `/v1/ws` |
| 网页 | `web/laser-station/` 全屏「实时取景标定」页：云台角/速度控件 + 两镜头画布 + 检测框 + 胶片 + 一键解算 |
| harness | `siteframing_test.go`：合成帧协议字节往返（类型序列 / 帧 meta+jpeg / 越界拒绝）。解算正确性沿用 `test_site_marker_calib` |

### 真机实测 + 踩坑（2026-06-15 已跑通）

端到端真机已验：HTTP 200、协议无截断、ArUco 检测在真帧上工作、解算正确报「公共标记不足」（未贴标记时）。
两个曾卡死的 bug 已修：

1. **★OpenCV 日志污染 stdout**：OpenCV 把 `[ INFO ] TBB backend` 写到 **stdout**，混进二进制帧协议
   → Go 把 `"[ INF"` 读成 1.5GB 长度 → unexpected EOF / 502。修：`cv::utils::logging::setLogLevel(SILENT)`。
2. **两单元并发 OpenCV race**：`detectFrame` 共享 static aruco dict + 并发 detect 崩溃（fast 调度崩、gdb 串行不崩）。
   修：`procMu` 串行化整条 cv 流水线（0.66fps 下零损耗）+ dict 传参。Go 端加 64MB 记录上限兜底。

### ★ 相机帧产出硬约束（决定可用性）

- **帧产出 ~60/扫（订正：早先"~6 帧上限"是 pipeline bug）**：相机 4003 满出 **0.33fps**（`device capture 4003 70s`
  实测 23 帧/0 坏帧 → ~60 帧/180s 扫）。`camera_fps` 是 0.33 硬上限（设 10 rate 不变），但 ×180s≈60 帧本就够。
  两个吃帧 bug 已修，取景现拿 **~60~90 帧/镜头**：
  1. **status-poll 饿死 recv**：取景旧传 `status_port=4000`，recv 循环里每 400ms 阻塞 HTTP 打 :4000 → 读太慢 →
     socket 满 → 设备丢 ~95% 帧。纹理路径 `status_port=0`+`push_back` 故从不缺帧。修：取景也 `status_port=0`
     + 独立状态线程轮询 device_status 判扫掠结束后 cancel（不在 recv 循环）。
  2. **全分辨率 aruco 太慢 worker 跟不上**。修：生产者-消费者解耦（采集只入队，单 worker 处理）+ 检测降到预览
     1280 分辨率（缩放内参 solvePnP，~9x 提速）。1~3m 的 150mm 标记在 1280 下 34~103px 仍可靠。
  - 启示：**0.33fps 是 rate 不是帧数瓶颈**；标定能拿几十帧、极稳。4001=ENC、4010=PTS，非更高速图像流。
- **★扫前 `update_control` 重设角/速度 → 随后 sweep 退化成 ~12s 短快扫 → 0~2 帧**；不重设用持久化配置 →
  sweep 慢而长（`[0,90]@0.5` 跑满 180s → 6 帧）。修：`applyFramingControl` 幂等 + 改配置后 4s settle。
  **推荐流程**：先用扫描配置面板把范围/速度设好（持久化），取景页用同值命中幂等跳过 → 慢长扫拿到帧。
- **标定够用**：标记标定靠少数高清帧覆盖标记即可（每帧 3840×2160、视野宽），不需几十帧；防 0 帧=慢扫/重试。
- 检测在 `on_img` 内联（0.33fps 余量足）；预览 1280 宽 / JPEG80，base64 走 NATS（体量可忽略，远低于 1MB）。

---

## 10. 已知不确定项（来自逆向 spec，标定时需留意）

逆向自二进制，下列项**尚未 100% 确证**，自建标定/复现时要小心（详见 `re/spec_calibration.md §8`）：

| 项 | 风险 | 说明 |
|---|---|---|
| U1 | **高** | P2Plane 第二维残差含义未定（残差维=2，只懂 `n·p+d`）。错了 → 雷达标定错 |
| U3/U12 | **高** | 航向 `Rz(±θ)` 符号 + 文件名 `<angle>` 是度还是弧度。符号/单位错 → 每个投影都错 |
| U2 | 中 | 平面行 `[6..8]`（疑似面片 `l,w,h` 包围盒）如何参与匹配/第二残差 |
| U11 | 中 | 具体 ArUco 字典 + markerLength。字典错 → 0 检出 → 标不了相机 |
| U4/U5/U6 | 中 | HuberLoss δ、固定块如何固定、`prior_translation` 用法。影响收敛/鲁棒，不改名义模型 |

> 这些都能靠**静态反汇编 + 用样本数据数值比对**解决，不需要真机；真机只用于端到端复现 `calibration.json`。

---

## 11. 术语表

- **靶 (target/fiducial)**：标定时几何已知的参照物，当真值反解参数。
- **编码标记 (ArUco/AprilTag)**：图案自带唯一 ID 的方形标记，解决"哪个像素=哪个 3D 点"。
- **重投影误差**：用当前参数预测的像素 − 实测像素，相机标定的目标函数。
- **点到平面残差**：点经变换后到已知平面的有符号距离，雷达标定的目标函数。
- **fixed_transform**：名义机械安装（config 常量，标定不动）。
- **corr_quat/offset**：真机精修 delta（标定优化对象），与 fixed_transform 合成 = rot_quat。
- **轴 (axis)**：云台转轴；各传感器外参都相对它定义，成像再叠加每帧航向角。
- **site 外参**：两单元 A/B 之间的相对位姿，用于融合，ICP 求，与相机靶无关。

---

## 参考

- 逆向 spec（外部 `/root/lilw/lidar/re/`，未迁入仓）：`spec_calibration.md`（含每步反汇编锚点 + 验证日志）、
  `geom_R8_calib.md`、`spec_texture.md`、`SPEC.md`。
- 在树实现：`src/calib/calibration_pipeline.{h,cpp}`、`src/texture/colorizer.{h,cpp}`、
  `src/config/{config_yaml,calibration_json}.{h,cpp}`。
- 设备/配置：`server/native/lidar/calib/{config_10x_live.yaml,calib_10x.json}`、`GOAL.md`。
- 关联架构：[15-laser-scanner-integration](15-laser-scanner-integration.md)。
