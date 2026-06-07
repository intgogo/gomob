# 激光车位框：按镜头独立框 + 第一视角漫游标注（M10）

**Why**：① 点云操作界面难用——A/B/融合三窗不统一、仅顶视拖框圈框反直觉。② 物理上每台相机看到的背景不同，单一世界框隔离不够干净。③ 用户要"走进点云走一圈圈出车位"的沉浸式标注。

**做了什么**（2026-06-05，分支 `feat/rgbd-stream-client`，提交 `117b81a..735f950`，6 阶段）：

- **统一窗口**：镜头 A/B 与融合三窗同款轨道查看 + 视角预设条（顶/侧/斜/自由 + 重置）。`LaserScanScreen.kt` 去掉"仅融合给预设条"限制。
- **服务端按单元框**：migration `0020` 主键 `bay_key → (bay_key, unit)`，`unit DEFAULT 'a'`（历史单框平滑迁为 a 框）。`GET/PUT/crop-preview ?unit=a|b`（缺省 a）；preview a→unitA 云、b→unitB 云。runner 双框时各单元云按各自框去背景 → unitB 经 `BToA` 并入世界系 → 隔离并集测量（`crop_box_dual`，**纯 Go 不动 C++ 融合**）。`transformPoints` 行优先 4x4 helper。
- **第一视角漫游标注**：`RoamAnnotationScreen` + `PointCloudSurfaceView` roam 模式（复用 Filament，不重建）。左虚拟摇杆走动 + 右半屏拖动转头/抬头低头；进"标注"后走过的地面足迹连线，完成时**凸包 + 最小面积外接矩形**拟合 OBB → `worldBox` → 转交 `LaserCropBoxEditor` 顶视微调（含翻转）保存。漫游**只用于标注**，平时轨道查看读测量。
- **入口**：点云窗口左下「漫游标注 ◈」，按当前选中镜头进——融合/A→`a` 框（up=地面法向，A==世界系）、B→`b` 框（up=+Z）。

**关键坐标系（最易错点）**：`unit_a` 云 == 世界/融合系；`unitB` 在自身设备系，`BToA` 服务端已算好。漫游路径 (u,v) 必须与 `projectTopView`/`worldBox` **同源**（`groundBasis(up)` 世界原点系，路径存 `walkU+originU`）；yaw 约定（积分器/相机/矩形角）三处一致。

**How to apply**：改激光标注/测量先读本条 + [[finding_laser_scan_angle_cannot_depth_isolate_2026-06-05]]（为何必须 3D 框裁剪）。几何镜像服务端 `cropbox.go`，复用 `LaserCropBoxEditor` 的 `groundBasis/projectTopView/worldBox/countInBox`（已改 `internal`）。验收：harness `laser_roam_cropbox`（walk→OBB 几何，host JVM）+ `go test ./internal/laser/...`（per-unit 存取/预览）。**真机走动/画框流畅度、look-pad+摇杆双指分流待用户在设备上复核**（开发期无连接设备，已编译 + 单测 + harness 兜底）。

**部署拓扑（2026-06-05 已部署验证）**：激光逻辑**只在 laserworker**，devserver 是 `/v1/scans/laser/*` 透明反代（`NewSingleHostReverseProxy`，自动透传 `?unit=`，改 unit 路由**不必动 devserver**）。重建只需：`server/scripts/laser-cgo-setup.sh`（软链 `/root/lilw/lidar/build/liblidar_scan.a`）→ `cd cmd/laserworker && CGO_ENABLED=1 go build -tags laser_cgo`（必须带 tag，否则走 `cgo_stub` 返 ErrCgoDisabled）。DB 在 **127.0.0.1:15432**（gomob-pg 容器映射），迁移 `GOMOB_DB_DSN=…15432 scripts/migrate.sh up`。服务从 `/tmp/` 直跑非 systemd：重启=抓 `/proc/<pid>/environ`（含 LD_LIBRARY_PATH/GOMOB_*/units IP）→ kill → `setsid nohup` 用同 environ 起新二进制（端口 18087）。**注意**容器内 psql 默认 socket 连的不是 15432 那个库，校验须 `podman exec gomob-pg psql -h 127.0.0.1 -p 5432`。
