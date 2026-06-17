# 现场共享 ArUco 标记场自标定多单元 site 外参 2026-06-15

## Why（背景）

激光双单元 A(.101)/B(.102) 拼进同一世界系，原只有两条路：冻结 `site_extrinsic.json` 或 ICP
（`registerTwoUnits`，靠点云重叠，对称车型会配歪）。本轮新增**现场共享标记场自标定**：现场贴
`DICT_APRILTAG_36h11` ArUco（**无需测量靶坐标/特制板**），两单元各 pan 拍图 → 解 B→A → 写
`site_extrinsic.json` → 现有 `align=site` 融合直接吃。区别于工厂"490 标记场 + 平面墙"的**单元
相机↔雷达**完整标定（那个本仓不重跑，靠厂商 Windows 工具）。

## How to apply（复用要点）

- **算法**（A 系=世界）：每单元图 → `cv::aruco::detectMarkers` → `solvePnP`(已知边长)得标记中心在
  相机系 → `cameraToWorld`(复用 colorizer 前向模型的逆)投到单元系 → 跨帧均值 → 公共标记
  `Eigen::umeyama(src=B,dst=A)` 求刚体 B→A。已知边长单视角即出深度，比小基线三角化稳。
- **在树**：`src/calib/site_marker_calib.{h,cpp}`（CORE 纯 Eigen 可 host 测 / FRONT-END OpenCV）、
  CLI `lidar_cli calib-site-markers <imgA> <cfgA> <calibA> <imgB> <cfgB> <calibB> <out.json> [len_m] [min_common]`、
  harness `tests/test_site_marker_calib.cpp`（精确复原机器精度；σ=2mm 噪声下 B→A 误差 0.9mm/RMS 1.6mm）。
- **现场流程**：打印已知边长 ArUco（量准）贴 ≥6 个重叠区(≥4 公共) → 每单元 `device capture ip 4003` +
  `replay img` 导出 `*_h<度>.jpg` → `calib-site-markers` → `site_extrinsic.json` → `align=site`。
- **★ OpenCV 雷点**：`/usr/local` 有 OpenCV **4.5.5（无 aruco）** 会被 CMake 默认选中导致 `src/calib`
  整个被排除（"calib sources EXCLUDED"）。必须 `-DOpenCV_DIR=/usr/lib64/cmake/OpenCV` 锁系统 **4.6.0
  （带 aruco contrib）**；`server/scripts/laser-cgo-setup.sh` 已锁。
- **架构边界**：解算器用 Umeyama+aruco+Ceres，**不进 cgo 精简库 liblidar_scan.a**（laserworker 保持无
  Ceres/aruco），以独立 `lidar_cli` 形态被调用。
- **一键现场标定已落地两形态**（均 laserworker exec 独立 `lidar_cli`，解算器不进 cgo）：
  ① 离线一键 `POST /v1/scans/laser/site-calib`（sweep→存图→`calib-site-markers`）；
  ② **实时取景** `POST /v1/scans/laser/site-framing`（边扫边推 RGB 帧+检测，看着对标记，见下）。
- **待办**：前端 solvePnP 光心系约定需真机图验证一次；可加 Ceres 联合 BA 提精度；加 RANSAC 抗离群。

## 实时取景标定（2026-06-15 续；§9.6）

**★ 硬件实测**：LTS-T1 相机 = IMX415 3840×2160 **静止相机**，fps≈0.33；**READY 静止停泊时连 4003
抓 4 秒 = 0 帧**——相机只在**云台转动时逐帧出图**，停下即停拍。所以这台**给不了真实时视频**，最接近形态
= 可控扫一段 + 边扫边推帧的胶片预览（用户拍板）。"云台控制"= 设扫描起止角+速度（单 pan 轴，无独立云台）。

- **链路**：网页 POST → Go 设角/速度(云台) → exec `lidar_cli framing-stream`(被动连两单元 4003) → 见
  READY 即 devctl SCAN_START → lidar_cli 逐帧 解码+aruco 检测+solvePnP+预览降采样 → **stdout 二进制帧
  协议** → Go 转 NATS `laser.frame`(owner 路由) → signaling 桥 → 浏览器 `/v1/ws` 渲染(画布+绿框+胶片) →
  扫完 umeyama 解 B→A 回 HTTP。与 cgo 同范式：**lidar_cli 被动、Go 持门控**。
- **协议**：`[4B BE N][1B type][N]`；`'m'` 帧=`[4B metaLen][meta json][preview jpeg]`、`'s'` 事件、`'r'` 结果。
  检测在**全分辨率**做，角点像素缩放回预览系；预览 1280 宽/JPEG80，base64 走 NATS（0.33fps 体量可忽略）。
- **在树**：`src/calib/framing_stream.{h,cpp}` + `lidar_cli framing-stream`、`internal/laser/siteframing.go`
  (`readFramingRecords`/`decodeFrameRecord`/`applyFramingControl`/`newFramingGate`)、`signaling/laser_bridge.go`
  加 `laser.frame`、`web/laser-station/` 全屏取景页、`siteframing_test.go`(协议字节往返)。
- **真机端到端已跑通（2026-06-15）**：HTTP 200、协议无截断、ArUco 检测在真帧上工作（A 实测认出标记）、
  扫完解算正确报"公共标记不足"（未贴标记时）。两个曾卡住的 bug 已修：
  - **★OpenCV 日志污染 stdout**：`[ INFO ] TBB backend` 文本被 OpenCV 写到 stdout，混进二进制帧协议
    → Go 把 `"[ INF"` 读成 1.5GB 长度 → unexpected EOF/502。修：`cv::utils::logging::setLogLevel(SILENT)`。
  - **两单元并发 OpenCV race**：detectFrame 共享 static aruco dict + 并发 detect 崩溃（fast 调度崩、gdb 不崩）。
    修：procMu 串行化整条 cv 流水线（0.66fps 下零损耗）+ dict 传参不用 static。Go 端加 64MB 记录上限兜底。

## ★ 相机帧产出硬约束 + 扫前重设雷点（2026-06-15 实测）

- **★★ 订正：早先"单次 sweep 只 ~2~6 帧"是本侧 pipeline bug，不是硬件限**。相机 4003 实际**满出 0.33fps**
  （`lidar_cli device capture .101 4003 70` 实测 **23 帧/70s、16MB、0 坏帧** → ~60 帧/180s 扫）。4001=ENC、4010=PTS。
  `camera_fps` 仍是 0.33 硬上限（设 10 不变，rate 调不快），但 0.33fps×180s≈60 帧本就够多。两个吃帧 bug 已修：
  - **status-poll 饿死 recv**：取景路径 `captureImageSweep` 传 `status_port=4000`，**recv 循环里每 400ms 阻塞式
    HTTP 打 :4000** → 4003 读太慢 → socket 缓冲满 → **设备丢 ~95% 帧**（只剩 ~2）。纹理路径传 `status_port=0`
    且 on_img 只 push_back（快）→ 一直拿满 ~60，所以纹理从不缺帧。**修：取景也 `status_port=0` + 独立状态线程
    轮询 device_status 判扫掠结束后 cancel（不在 recv 循环）**。改后实测 **A=63 帧**。
  - **全分辨率 aruco 太慢 worker 跟不上**（~1.8s/帧 vs 0.33fps×2 到达）→ 处理滞后、不出结果。**修：生产者-消费者
    解耦（采集线程只入队原始 JPEG、单 worker 处理）+ 检测降到预览 1280 分辨率（缩放内参 solvePnP，~9x 提速）**。
    改后 worker 跟上、自然收尾出结果。1~3m 的 150mm 标记在 1280 下 34~103px 仍可靠检测。
  - 现状：取景一趟 **~60~90 帧/镜头**（够标定极稳），状态线程在双扫掠结束 +5s 宽限后收尾。
- **★扫前用 `update_control` 重设角/速度 → 随后 SCAN sweep 退化为 ~12s 短快扫 → 0~2 帧**（相机抓不到）；
  **不重设、用持久化配置 → sweep 慢而长**（`[0,90]@scan_speed=0.5` 跑满 180s → **6 帧**）。`scan_speed=0.5`
  能 stick 且持久态下真慢；坑只在"扫前刚改"那一下。机理未深究（疑似改配置触发快速回中/校验扫被采集逻辑误截）。
- **修复**：`applyFramingControl` 幂等（已是目标角/速度不重发）+ 改配置后 4s settle。推荐流程：**先用设备
  扫描配置面板把范围/速度设好（持久化），取景页用同值→命中幂等跳过→慢长扫**；或同配置连扫第二趟即慢。
- **标定够用**：标记标定靠**少数高清帧覆盖标记**即可（每帧 3840×2160、视野宽），不需几十帧；防 0 帧失败=慢扫/重试。

详见 [[finding_laser_a_station_texture]]、`docs/architecture/17-laser-camera-lidar-calibration.md §9.5/§9.6`。
