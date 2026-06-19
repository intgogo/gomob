# 抠车隔离：原厂靠固定标定框 + gomob 用空工位背景相减（路 B，全自动）

## What

真机融合云外廓测量"明显不准确"的根因与解法（2026-06-17）。

### 根因（已用真机 scan168 复现）

融合云是**整个房间**（实测 X/Y/Z 跨度各 ~3m、质心远离原点），车没被空间隔离出来。三条自动路径都错：
- **device_roi**（vendor 硬编码 ROI 270..1000 等）：那是 JCHY **设备系**专用，gomob 融合云在 `unit_a` **世界系**（做过 z 翻转 + 融合变换），盒子根本没对上 → 裁出无意义薄片（L=135）。
- **ground 地面相对**：去地面后**最大连通簇仍横跨地面到天花**（Z=3074），车通过地面/墙/杂物跟房间**连成一个整体**，欧式聚类抠不开 → 量成房间（L≈3159 H≈3103）。
- DB 里**一个车位框都没有** → runner 落到上述回退 → 把房间尺寸当车显示。

**结论：屋内单帧融合云里，纯几何无法把车从房间自动抠出来。必须有空间隔离手段。**

### 原厂（JCHY）怎么抠车 —— 固定标定的裁剪框，不是自动抠

逆向直证（docs/16 §6.3 + 管线②）：JCHY 抠车=一步 `PassThrough` 体裁剪到**固定盒子** `setting.ini [Param] xmin=270 xmax=1000 ymin=0 ymax=2200 zmin=10 zmax=800`（`CalibSetting.ini` 还分 `[S_CAR]`/`[L_CAR]` 两套）。证据：原厂 `1.pcd` 点全落 `X[311,923]⊂[270,1000]`。**精度高正因这固定盒子**：扫描仪+工位钉死不动→车每次落同一设备系盒子→裁掉墙/地/背景→再聚类+OBB。原厂**没做**"从杂乱场景认出车"。

### gomob 解法：路 B 空工位背景相减（全自动，比原厂更省标定）

固定安装 → 扫一次**空工位**当背景，之后每次扫描"在背景 tol 内有近邻"的点=静态房间剔除，无近邻=车。利用了 gomob 跟 JCHY 同样具备、但 JCHY 没吃透的"扫描仪不动"条件，且不需逐车型标定 ROI。

- 算法 `server/internal/laser/background.go` `SubtractBackground(live, bg, tol)`：背景体素哈希(leaf=tol)+27 邻域精确距离，O(n+m)，PCL-free。
- 存储：背景=点云走 **MinIO**（稳定 key `laser-scans/background/<bayKey>/fused.pcd`，bayKey=unit_a_ip），**不进 postgres**（2M 点 JSONB 会爆）。重采直接覆盖同 key。
- 采集：扫描请求带 `mark_as_background=true` → runner 融合后把 cloudFus 存背景 key、跳过测量、事件 `background_captured`。
- 测量优先级：`crop_box(显式框) > bg_subtract(有背景) > 无隔离→measure_valid=false 诚实闸`（不再 device_roi 误测房间）。
- 网页：顶栏"采集空工位背景"按钮 + 状态小标；无背景时测量面板提示"需采集背景"。

## Why

用户拍板**路 B**（"你无法把车辆点云直接抠出来吗""原厂怎么实现的"两连问后）：原厂精度来自固定框而非魔法分割，固定框不是妥协而是行业最优；背景相减是同条件下更自动的超越。符合"第一性、参考行业最佳再突破、不靠手画框妥协"。

## How to apply

- **tol 规律（harness 实证）**：`tests/harness/laser_background` 扫参证 **tol 必须 > 传感器噪声+配准漂移**，否则背景残留。±15mm 噪声下 tol=10/20 残留墙/天花、tol≥30 干净；默认 **40mm** 有余量。换设备/工位若漂移大需重标 tol（设 `LIVE_PCD`/`BG_PCD` 跑真机闭环判定）。
- **融合云重建陷阱**：runner 把测量用的 `cloudFus` **从 `cloudA ∪ BToA·cloudB` 重建**（丢弃 native 融合帧，runner.go ~563）。背景相减作用在这条重建融合云上；测试要把场景经 unit0/unit1 分镜云喂入、BToA=单位阵，光发 unit2 会被丢弃。
- **真机验收待补（设备门控）**：合成 + 接线已闭环（`background_test.go` 减出车 L/W/H 准、`runner_bg_test.go` 采集→存→读回→相减整链），但**真机需用户扫一次空工位+一次有车**复核背景相减 + 网页叠加对位。
- 相关：[[finding_jchy_measurement_layer_re_2026-06-04]]（原厂 8 阶段管线/PointSIFT 不可得）、[[finding_vehicle_axle_ground_contact_2026-06-17]]（轴距/货箱/叠加几何）。
- 代码：`server/internal/laser/{background.go,runner.go(测量优先级+capture+loadBackground),handler.go(GetBackground+mark_as_background)}`、`web/laser-station/{app.js,index.html,styles.css}`、`tests/harness/laser_background/`。
