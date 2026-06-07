# 激光双单元缩扫描角隔离不了目标，唯一解是 3D 框软件裁剪（2026-06-05）

## 结论

LTS-T1 双单元（.101/.102，Pico100 lidar + 转台）**没有按深度/距离裁剪的能力**——
设备只有两个角度闸门：**pan 水平角**（`scan_start/stop_angle`）+ **竖直俯仰角**（`lidar_filter_zone`，
注意它是**竖直角范围**不是径向距离）。因此"圈出想要的点云范围→反算合适的扫描角度→下次只扫这块"
这条路**物理上做不到**：任何扫描角组合都无法把目标和它视线方向上更远的背景分开。

唯一能按深度隔离目标的是 **3D 框软件裁剪**（持久车位框 + 每次扫描裁框内）。

## Why（真机 scan24 实测，594282 点融合云）

把 unit_b（"办公室全扫上"那台）的点按径向距离分成"近处车"(<2.5m) vs "远办公室"(>4m)：

| | pan 水平角 | 俯仰角 | 径向距离 |
|---|---|---|---|
| 车（14.5万点） | **[-180,-164] 全程** | [-38°, 88°] | 1.4–2.5m |
| 办公室（4.3万点） | **[-180,-164] 全程** | [-25°, 22°] | 4–14m |

- 缩 pan：车和办公室**都铺满整个 pan 区间** → 删不掉一个办公室点。
- 加俯仰角闸门：办公室俯仰 [-25,22] 完全落在车的 [-38,88] 里 → 立体角联合后办公室仍剩 **99.3%**。
- 根因：办公室在传感器视线方向上就**藏在车的同一立体角里**（车前后/周围更远处），角度分不开深度。
- 几何补充：单个 pan 角是一个**固定横向 x 的竖直切片**(y-z 面)，pan 扫掠=横向移动切片；
  corr(h_angle, x)=-0.31（最强但弱），缩 pan 只裁横向、裁不掉前向深度。

附带坐实：scan24 自动地面 RANSAC 又拟到天花（normal≈+Z 但 d=-1430→"地面"在云顶，99% 点在其下方），
measure 出 643×19×36mm 垃圾尺寸——**自动地面在真机现场不可靠**，范围必须由用户定。

## How to apply

- 不要再为激光做"圈范围→反算扫描角"。要隔离目标用 **3D 框软件裁剪**（M9.11 `cropbox.go`）。
- 框定义在 **unit_a/融合世界系**（两单元螺丝固定→跨扫描稳定），不依赖自动地面；up 由用户从地面法向
  种子起翻转/微调（自动地面常拟到天花，必须可翻转）。
- 测量优先级：**持久车位框 > 自动地面 > 设备 ROI**（`runner.go`）。
- 单元云存 XYZI（intensity=每点采集 h_angle°）仍有价值（调试/可视化采集角），但**不要**用它反算扫描角做隔离。
- 设备 `lidar_filter_zone` 改不出深度裁剪，别往那使劲；要快可选地把 pan 夹到目标方位区间（仅省横向无关扫程，非隔离主力）。

## 安全改扫描角的路径（2026-06-05 实操）

- **改 pan 扫描角用 `POST /v1/scans/laser/device-scan-settings?unit=a|b`**（handler → `dev.UpdateControl`，RE spec 确认**非破坏**）。它是**独立单发**，不碰 M9.7 那个雷——M9.7 弄瘫 A 的真因是 `UpdateControl` 紧贴 `SCAN_START`（A 移到起始角即 READY 不进 SCAN），**不是角度值**。所以 `SET_SCAN_ANGLES=false` 保持，靠这个端点把角度**持久化**进设备，下次扫描自动用。
- **必须完整回写 control**：handler 直接 decode body 覆盖，漏字段会被零值清掉（scan_speed→0 等）。先 `GET device-info` 读全 8 字段（scan_speed/zero_speed/scan_start_angle/scan_stop_angle/watching_angle/lidar_filter_ghost/lidar_filter_zone/camera_fps），只改起止角再整体 POST。
- **⚠️ 设备扫掠 `start→stop` 走「短角向」，不是单调增——范围绝不能跨 ±180 环绕**。机械软限位 `limit_soft_min/max=-180/180`（RE 抓包），但**有效 pan 范围必须是不跨 ±180 的、跨度 <180° 的弧**。实测踩坑（job 43）：A 设 `-179→179` 本想要 358° 全圈，设备却走 2° 短路（-179→±180→179），只扫到 ±180 边界一条**固定 x≈-65mm 的退化薄片**（121k 点全挤在 h_angle ±180 两端，中段 0 处=车所在=零点）。**空扫守卫 `minSweepDeg=10` 抓不到**：环绕使 `max-min` 跨度算出假 360°（runner.go span 用裸 min/max）。→ 要宽就 `0→170` / `-80→90` 这类不跨 wrap 的弧（扫过场景零点方向），别 `≈全圈`。改角后设备 BUSY 摆到新起始角（实测 -179→0 平滑过中段，证电机本身没问题），到位 READY。
- **`error_code:32` 是两单元基线良性值**（B 顶着 32 也 READY/"ready for everything"），不是故障，别误判。位名序 `ZERO HEAT TEMP LIMIT GAP ENC MOTOR`，真故障看 LIMIT(8)/MOTOR(64)。
- **A(.101) 相机偶发 offline**（`camera_online:false`/`scan_msg:"camera offline"`），但**不挡 lidar 扫描**（仍能 READY）；只是 A 这路无颜色。要彩色查 A 相机排线或断电重上电。2026-06-05 把 A 从 0→90 试过 -179→179(退化,见上)→改回非环绕 0→170。
- **诊断退化云的手法**：下载单元 PCD（`GET /{id}/cloud/unit_a`，owner uid 鉴权，DB `laser_scan_jobs.owner_user_id`/`unit_a_object_key`），解 XYZI binary，看 **x 跨度**（退化=几十 mm 薄片）+ **h_angle(intensity) 直方图**（退化=只在 ±180 两端，正常=连续铺满弧）。光看总点数/裸 span 会被环绕骗过。

## 「扫描范围/覆盖」的真正指标 = x 跨度，不是扫掠角（2026-06-05/06）

- **判断单元是否盖满整车，看点云 x 跨度（≈车长 5m），不是 h_angle 扫掠角**。机位不同→每度覆盖差异大：实测 A 要扫 36° 才盖 5m，B 只扫 16° 就盖 5.2m。**别用扫掠角对称性判断好坏**（曾据此误判"B 也卡住要提速"——错，B 16° 本就盖满整车，不用动）。
- **`scan_speed`(deg/s) 是覆盖瓶颈，不是 scan_stop 角**。采集窗口近似定长，实际扫掠角 ≈ scan_speed × 窗口。`scan_speed=3` 太慢→A 只转 ~21°→只盖 1.3m（用户报"范围比原厂少太多"的真因之一）。提到 15→盖满 5m（x: 76mm→1310mm→5050mm 三级跳）。要更宽就再提 speed，不是改 scan_stop。代价：speed 越高每度点越稀（speed3=19500 pts/°，speed15≈3900）。
- 修一个单元覆盖的标准动作：① 角度非环绕（见上，如 0→170）；② scan_speed 提到够在窗口内盖满车（~15 起）。两者都经 `device-scan-settings` 完整回写 control，`SET_SCAN_ANGLES=false` 下次扫自动用。

相关：[[finding_multiview_rgbd_pivot_2026-05-07]]（端云融合主线）、[[finding_laser_roam_percamera_cropbox_2026-06-05]]（扫满后靠车位框裁背景）；车位框实现见 `server/internal/laser/cropbox.go` + `feature/scan3d/.../LaserCropBoxEditor.kt`。
