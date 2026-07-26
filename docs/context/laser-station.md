# 激光工位扫描 · 标定 · 车辆测量 — 历史上下文

> 最后更新: 2026-07-26 | 截至 commit: a979415 | 维护规则见 AGENTS.md「历史上下文维护」节

## 使命与当前状态

把两台网络激光扫描单元 LTS-T1 (.101 = A / .102 = B, HTTP :4000 + TCP 流 :4010/:4002/:4001/:4003) 组成固定"3D 扫描工位": 双机采集 → 服务端配准融合 → 空工位背景相减抠车 → 车辆外廓 L/W/H + 轴距/前后悬 + 货箱尺寸测量 (对标原厂 JCHY / GB7258), App 与网页工位台只做显示与操作。几何真理源是逆向仓 `/root/lilw/lidar` (byte-verified), 测量真值基线是 JCHY 原厂会话 100742 模型车 (L1777/W533/H759, 总轴距 1370, 前后悬 261/163), 部署与测试全在宿主机 192.168.9.160 (本机无 Go)。

当前阶段: **M8' 服务端链路完成、M9 常规几何测量大部分完成、M13 精度收敛算法层达标** (真机 3 连扫 σ≤2.8mm、L/H 误差 ≤1%), 网页工位台可采集/标定/融合/漫游并叠加工程图式尺寸标注。**但生产验收未闭环**: M13.13/14/16/18 四个 P0 红项 (lease、安装 epoch、ArUco 生产门、精修对应质量门) 未做, M13.7 现场重标与 M13.12 同 revision 三连扫真机门等用户确认空工位后执行。上述 M8'-A6/A7、M13.10/M13.12、07-01 App 瘦身与 07-10 StationSwitcher 已在 a979415 落盘；`fix/laser-app-web-parity` 的 3 个提交保留为历史分支，不再有待合并工作区。命名雷点: TODO.md 里有两个"M8"——`M8(25fps)` 是 Berxel 取流优化 (见 docs/context/berxel-p100r3-native.md), 激光端侧集成的 M8 已被 M8' 取代。
工位管理台登录已同步收口：仅接受部署侧高熵口令，删除可推算日期口令；安全约束与 M11.2 记录见 `docs/context/infra-server.md`。

## 决策时间线

### 2026-06-03 激光设备进 gomob: 端侧 M8 立项即被 M8' 服务端下沉取代 (M8/M8')
背景: 车辆外廓页要接激光双单元, 首版拍板"Kotlin 网络 + native Eigen-only 几何 + 端侧融合" (不引 PCL, ICP 复用 reconstruction/IcpRegister), M8.1 native/lidar 内核落地 (commit dbffbbb)。同日用户再拍板**架构变更 M8'**: 激光连接/采集/融合全部下沉服务端, App 瘦客户端只显示+操作 (commit 9a9be16)。当天 13 个激光 commit 打穿全链: cgo 链 `liblidar_scan.a` 流式点回调 (G3, 9638e18) → laserworker :18087 + runner + MinIO/NATS (G4, 1072063) → ws 推流 + gateway 路由 (G5, 2fa84d5) → App 三云页 (A1-A3, 384b92a) → devserver 并入激光反代 (D, fde8b7a) → 真机 live 融合 154074 点 union 精确 (E1, f4eb325)。端侧 M8.2-M8.8 作废, M8.1 保留休眠。证据: TODO M8/M8' 节、`docs/agent-memory/finding_laser_scanner_integration_2026-06-03.md`、docs/architecture/15 §9。

### 2026-06-04 "ICP 不可信"定调 + 设备控制面 + 真机链路修复 (M8'/M9.7-9.10)
融合视图全黑真因是 autoFit 被垃圾点拉飞 + ICP 对固定双机位空场即发散 (把 B 甩出 17m), **align 默认 icp→none, 固定双机位正解 = site 标定外参** (commit 7297314)。补服务端/App 设备控制面 (原厂功能键, 230c986/c2bdf5f, 破坏性操作必须二次确认——共享物理设备安全铁律); 交互打磨相机式三键底栏 (2635cd7)。M9.7 修真机链路: 设备偶发吐 1e37mm 垃圾 PTS 双层兜底、**A 不扫根因 = 起扫前 UpdateControl 改角度把 A 弄瘫** → 关 SET_SCAN_ANGLES 用设备持久化角度。M9.8-9.10: RANSAC 地面 (重力先验防选墙) + 视角预设 + 测量改"地面相对、坐标系无关" (根治 roi_pts=0——JCHY 硬编码 ROI 与真机坐标系零重叠)。证据: TODO M8'-A4/F、M9.7-9.10 各行。

### 2026-06-05 "反算扫描角"被真机证伪 → 3D 车位框 + 车型贯通 (M9.11/M9.12)
用户要"圈点云范围→反算扫描角只扫车"。scan24 实测证伪: 设备只有 pan/俯仰角闸门、**无深度闸门**, 背景与车在同一视线立体角, 缩角后远背景仍保留 99.3% → 唯一可行是 3D 车位框软件裁剪 (框定义在 unit_a/世界系, 跨扫描稳定)。落地 cropbox.go + migration 0019 + 顶视手势拖框编辑器, 测量优先级 持久框 > 地面 > 设备 ROI; M9.12 打通 JCHY 26 车型目录 (carType XOR 解密) + carType 偏移 + 车型下拉。证据: `finding_laser_scan_angle_cannot_depth_isolate_2026-06-05.md`、TODO M9.11/9.12、merge 91921e3。注意后续订正: crop box 在 2026-06-17 后降级为兼容 fallback (生产主线是背景相减), 车型下拉在 07-01 被撤。

### 2026-06-05→06-06 M10 点云界面重做 + 第一视角漫游标注 (M10)
用户反馈"点云操作界面还是很难用", 三决策: A/B/融合三窗同款轨道; 车位框**按镜头独立框** (每台相机看到的背景不同, migration 0020 主键加 unit, 双框各自去背景再并集测量, 纯 Go); 标注走**第一视角漫游**"走一圈"→凸包+最小面积外接矩形拟合 OBB。117b81a→7226866 共 20 commit: 漫游双摇杆/圆弧转身键、点云默认竖直翻转约定 (设备倒装)、部署拓扑记忆收口。坐标系最易错点 (unit_a==世界系、漫游路径与顶视投影同源、yaw 三处一致) 见 `finding_laser_roam_percamera_cropbox_2026-06-05.md` (含 .160 部署拓扑/重启配方)。**注意: 07-01 起漫游/车位框 UI 入口已从 App 撤除** (配置归网页端), 服务端按单元框能力保留。

### 2026-06-15 A 站纹理补齐 + ArUco 共享标记场自标定 + 实时取景 (M8' 标定线)
"镜头 A 没颜色"长期被当硬件问题, 真因是**软件链路只为 .102 写过**, runner 把 A 硬涂中性灰; 补 emitColorizedUnit 按 unit 取 config/calib 后 A 出真实色 (mapped≈30% 与 B 持平)。服务端 lidar C++ 源迁入仓内 `server/native/lidar/`。新增现场共享 ArUco 标记场自标定 B→A (solvePnP+umeyama, 无需特制靶), 与实时取景标定 (lidar_cli framing-stream → NATS → 网页胶片预览); 实测硬约束: IMX415 相机 0.33fps 且**只在云台转动时出图**; "扫前 update_control 重设角度→sweep 退化 12s 短快扫 0 帧"雷点 + status-poll 饿死 recv 丢 95% 帧已修。证据: `finding_laser_a_station_texture_2026-06-15.md`、`finding_laser_site_marker_calib_2026-06-15.md`、docs/architecture/17 §9.5/§9.6。

### 2026-06-17 标定订正 4 角点 6DoF + 网页工位台 + 轴距/货箱几何测量 (M9.4 系)
用户反馈"标定之后点云还是没对齐": 旧解算只取每标记**中心点** umeyama, ≤4 个共面标记下旋转欠约束, 外参偏 ~20°/66cm 而 RMS 自洽看着小; **修法 = 每标记 4 角点 (带朝向) 求解, 单标记即完全约束 6DoF** (`finding_laser_site_marker_corner_pose_2026-06-17.md`)。同日落激光工位后端 + 网页工位台 (896b6f3/c424cf3, web/laser-station 采集/标定/融合/漫游)。M9.4/4b/4c (a3b6749/f4023be/279a305): **轴距 = 贴地接触带密度峰** (轮是唯一触地部件; 关键陷阱: 轴检测必须跑在 largestCluster 之前, 否则悬挂轮被当独立小簇丢掉)、**货箱 = 顶高剖面 + rim 外长宽 + 恒宽 z 段定 bed**, 全几何无 DL (原厂 PointSIFT 权重不可得, 见下文禁区); 融合后自动测量 + 网页 SVG 叠加。证据: `finding_vehicle_axle_ground_contact_2026-06-17.md`、TODO M9.4/4b/4c。

### 2026-06-18 空工位背景相减自动抠车 (M9.13, 路 B)
crop box 之外的全自动路线: 保存空工位背景, 扫描后相减出车辆前景 (4149e24)。后续订正 (2026-07): 单个 fused PCD 相减不成立 (丢 A/B 设备系与 region 语义), 终态 schema = `region_cropped_unit_frames_v1` ——服务端 region 先裁 A/B, 分设备系相减, 仅 B 前景用最终 B→A 合并; 旧背景保留为显式 `legacy_fused` 兼容。生产顺序与 fail-closed 门见 `finding_vehicle_isolation_bg_subtract_2026-06-17.md` (该文件 07 月已更新为终态)。

### 2026-07-01 App 瘦身为纯操作端 (已在 a979415 落盘)
网页工位台成熟后, 用户拍板 App 端简化: 去车型下拉 (暂只算 L/W/H, `repo.start()` 零参)、加放大结果卡 LaserResultPanel、右上角改下拉「3D 工位 / 3D 相机」、**设置/设备控制/扫描参数/标定/车位框/漫游标注入口全部移除** (配置归网页端)。删 4 产品文件 + 2 孤儿单测 (含 core/data VehicleTypeCatalog.kt); 数据层 crop-box repo/api 保留; PointCloud3dView 漫游状态机 (~130 行) 惰性保留待独立清理。教训: 删产品代码必须同步删其单测且验证覆盖 test 源集。证据: 会话消化稿 2026-07-01, 改动已在 a979415 落盘。

### 2026-07-09 M13 精度收敛: "多次扫描误差大"四根因 (M13.1-13.11)
起因: 同一静止物体多次扫描 L/W/H 漂 9/20/11mm 且 L 系统性 +3.5%, 而点云自身重复性只有 1mm——误差全在管线不在采集。四根因 (job183-185 定量证实, 互换地面即复现对方读数一锤定音): ①**地面逐扫描 RANSAC 重拟合 = 主方差源** → 地面从背景 revision 持久化重建, live 只做漂移比对 (>1.5°/50mm 禁 measured); ②宽度 10mm bin 量化 → 1mm bin + P0.5/99.5 分位跨度; ③**两单元只见对立面, native 点到点 ICP 沿车长轴错 67mm** → Go 点到面 ICP + 法向相容性精修 RefineBToA (守卫 150mm/5°, 生产门 pairs≥1000/RMS≤15mm/Δt≤50mm/ΔR≤1°); ④背景相减吃掉贴台面车底 → 车高改"车顶 − 支撑面"。新链 job186-188: L −0.39% σ2.8 / W −1.51% σ0.5 / H +0.65% σ0.4 (W 残差归因现场标定 → M13.7)。追修: M13.8 轴距足迹裁剪 + 合理性闸 (修"总轴距 1862 > 车长 1768"物理不可能数, ef3fa2f); M13.9 接触带**锚定车体主簇底 P0.5−40mm** (证伪"箱子高低不平"假设——真因是过期背景残留伪结构致 4 轴全漏, 60eaf76); M13.10/10b 网页叠加工程图式尺寸标注 (双端箭头尺寸线+立体徽章, c32eca6/5e02d25); M13.11 货箱 bed 判据改**顶锚定** (旧"全局最长恒宽段"被底盘偶合段抢走致检出 ✓✗✓✗ 闪烁) + 伪轴双闸 (端部排除+轮对形态, 1164cf6, job196 终验 4 轴+货箱六扫箱长一致)。主修复 commit 3dd34c1/5c1b08a, 配套 `cmd/laserreplay` 离线复算 + harness `laser_repeatability`。证据: `finding_laser_dimension_error_rootcause_2026-07-09.md`、会话消化稿 2026-07-09_a82ee85f、TODO M13。

### 2026-07-10 工位下拉 + M14.5 外廓工位 UI 重排 (已在 a979415 落盘)
右上角下拉从"3D 工位/3D 相机"改为"3D 工位选择" (Berxel 相机入口暂隐藏), 工位列表**客户端静态先行** (只有 .160 一个真实工位, TODO 指终态服务端工位注册表); DeviceSwitcher 骨架保留可恢复 (是否/何时恢复 Berxel 入口无用户决策记录)。同日 M14.5 设计对齐把 LaserScanScreen UI 层重写为分镜 2×2 网格 (镜头 C/D 显式标"未接入"非假数据) + 状态 pill + 双槽操作条 + 尺寸叠加开关, 投影数学/状态机保留——UI 细节归 docs/context/app-ui-designsystem.md。证据: 会话消化稿 2026-07-10 两篇。

### 2026-07-11→07-13 生产权威化收尾 (已在 a979415 落盘)
三件事: ① **M8'-A6 长扫内存契约** ——App 无界累积 44s 稳定 OOM → 固定容量嵌套体素 + 服务端权威 PCD 派生有界样本 (`finding_laser_preview_memory_bound_2026-07-11.md`, scan209 严格 PASS); ② **M8'-A7 工位外参服务端权威化** ——旧网页把外参存 localStorage、Android 空请求被静默降 raw; 且 job209/210 证明仅统一 site 不够: App 未裁 live 减已裁背景, 700 万区域外点被当车辆 → `laser_site_calibration`/`laser_region_calibration` 服务端唯一真理源, **site 的 native 点到点 ICP 彻底删除** (scan208 离线验证删除后仅差 0.010mm/0.067°), 唯一精修 = 有守卫的 Go 点到面 (`finding_laser_site_calibration_server_authority_2026-07-11.md`); ③ **M13.12 App/Web 同源一致性** —— measured.pcd 用 MeasuredCloudArtifact (schema+SHA-256+全 revision+B→A hash) 固化身份, 任一错配整体失效; M13.10 尺寸标注 App 同源回迁 (07-13, Filament 真实 view-projection 锚定线框, harness laser_render_stability PASS; 该回迁已随 a979415 进入 master; 原分支提交仅作历史)。同批出账 M13.13-18 红项与 M9.6 合规结论撤销。07-12 用户给过**仅限联调的临时 site 质量豁免** (结果必须 production_eligible=false, 不得计入验收)。证据: 分支 commits ba49fca/0015a8c/5fc81b0、a979415、docs/architecture/15 §0。

## 禁区与已证伪路线

- **端侧激光方案 (M8.2-M8.8) 不要复活**: 2026-06-03 用户拍板全部下沉服务端; `native/lidar/` 仅 M8.1 内核休眠保留。证据: 9a9be16、TODO M8 节标题。
- **不要用点到点 ICP 做双机配准**: 固定双机位各只见车辆对立面 (~60mm 幕帘零重叠), 点到点 ICP 有系统性对立面偏置 (+3.5% 车长); 06-04 align 默认 icp→none, 07-11 native 点到点 ICP 已删除。唯一精修 = Go 点到面 + 法向相容 + 标量门, 且**禁止把单次 refine 回填 canonical site**。证据: 7297314、finding_laser_dimension_error_rootcause §3、M13.3。
- **不要再试"反算扫描角隔离车辆"**: 设备无深度闸门, 缩角后背景保留 99.3%; 扫描角只管覆盖与密度。证据: finding_laser_scan_angle_cannot_depth_isolate。
- **不要逐扫描重估固定安装基准量** (地面/外参), **不要用极值/单 bin 边界当测量输出**: M13 主方差源铁律。证据: finding_laser_dimension_error_rootcause "How to apply"。
- **标定不要只用标记中心点 umeyama**: ≤4 共面标记下旋转欠约束偏 20° 而 RMS 自洽; 必须 4 角点 6DoF, 生产门 common≥4/RMS≤5mm; **重标 site 后必须重采 raw A/B 背景**。证据: finding_laser_site_marker_corner_pose。
- **不要让客户端持有/覆盖工位配置**: localStorage site、客户端自行裁剪或重算都已造成过 3558×144×3107mm 级错误; site/region/背景/acquisition profile 唯一真理源在服务端, 客户端旧字段只做一致性校验。证据: finding_laser_site_calibration_server_authority。
- **背景相减不要用 fused-minus-fused 当新路径**: 丢设备系/region 语义; 新背景只能是 `region_cropped_unit_frames_v1`, 旧的显式标 legacy_fused, 不兼容时起扫前 409 fail-closed。证据: finding_vehicle_isolation_bg_subtract。
- **不要出通用限值"合规结论"**: 12000/2550/4000 固定上限无业务语义已撤销; M13.17 前必须 compliance_determined=false, 两端不显示合规徽章。证据: TODO M9.6/M13.17。
- **不要走 PointSIFT/DL 轮分割路线**: 原厂模型权重不可得 (models/ 空); 用户拍板几何路线 (贴地接触带) 为最优解非妥协。证据: finding_vehicle_axle_ground_contact "Why"。
- **App 点云禁止无界累积/全量复制**: 44s 稳定 OOM 实测; 权威云在服务端, 端侧只拿有界样本, 也不许用 keep_ratio 偷降服务端保真度。证据: finding_laser_preview_memory_bound。
- **轴检测不要跑在 largestCluster 之后** (悬挂轮丢簇漏轴); **货箱 bed 不要用全局最长恒宽段** (被底盘抢走闪烁), 用顶锚定。证据: finding_vehicle_axle_ground_contact、M13.11。
- 操作雷点: 扫前 update_control 重设角度→sweep 退化 0 帧 (先在设备面板持久化配置); OpenCV 必须锁 `/usr/lib64/cmake/OpenCV` 4.6 (⁠/usr/local 4.5.5 无 aruco 会被 CMake 误选); 共享物理设备破坏性命令 (SOFT_REBOOT/标定写入) 必须用户显式批准, agent 不自主执行。证据: finding_laser_site_marker_calib、M13.16、finding_laser_scanner_integration。

## 关键资产指针

- `server/internal/laser/` — 激光服务端核心: runner 编排 / measure.go / axle.go / cargobox.go / cropbox.go / overlay.go / ground.go / refine_btoa.go / devctl.go / siteframing.go / cgo 绑定 (`-tags laser_cgo`, 无标签走 stub 显式返错)。
- `server/cmd/laserworker` (:18087) / `server/cmd/laserreplay` (生产管线离线复算) / `server/scripts/laser-cgo-setup.sh` (在树 C++ 构建+软链, 锁 OpenCV 4.6)。
- `server/native/lidar/` — 服务端 PCL C++ (lidar_scan/lidar_cli/site_marker_calib/framing_stream) + `calib/` 标定资产; 大体量 re/ 与样本留 `/root/lilw/lidar` (无版本控制, 换机要备份)。
- `native/lidar/` — 端侧 Eigen-only 内核 (M8.1, 休眠)。
- `web/laser-station/` — 网页工位台 (采集/标定/取景/融合/漫游/尺寸标注 renderOverlayDimensions)。
- App: `feature/scan3d/.../LaserScanScreen.kt` / `DeviceSwitcher.kt` (StationSwitcher) / `PointCloud3dView.kt`; `core/data/.../LaserScanRepository.kt`; `core/network/.../LaserScanApi.kt`。
- harness: `tests/harness/laser_repeatability` (真值+σ 门) / `laser_background` / `laser_background_transaction` / `laser_app_web_parity` / `laser_ab_refine` / `vehicle_axle` / `vehicle_cargobox` / `vehicle_measure` / `laser_roam_cropbox` / `laser_live_preview_memory` / `laser_render_stability`（上述收尾 harness 均已在 a979415 落盘）。
- 架构文档: `docs/architecture/15-laser-scanner-integration.md` (§0 现行生产合同 = 最新权威) / `16-jchy-vehicle-measurement-app.md` (JCHY 逆向, §10 未坐实项) / `17-laser-camera-lidar-calibration.md` (标定原理+§9.5-9.8)。
- agent-memory: 上文时间线引用的 `finding_laser_*` / `finding_vehicle_*` / `finding_jchy_measurement_layer_re_2026-06-04.md` 共 12 篇。
- auto-memory `project_laser_deploy_host_160.md` — .160 服务布局/pg 15432/MinIO 卷直读剥 bitrot 头/工位台 cookie/JCHY 真值摆位。
- JCHY 真值: 数值已固化在 TODO M9 节头 / auto-memory project_laser_deploy_host_160 / harness 入参 `GOMOB_LASER_TRUTH_LWH="1777,533,759"`; 原始会话盘 `/root/WindowsR/JCHY_OFFLINE/Data/100742/` (Result.ini + 双镜头 PCD) 本机已不存在 (环境重置后未恢复), TODO.md 与 docs/architecture/16 中该路径引用现已失效。

## 未竟事项

- **M13.13 (P0 红)** laserworker 工位 lease 与崩溃恢复: DB station-scoped lease + fencing token, SIGKILL 后任务不悬挂、设备不失控。
- **M13.14 (P0 红)** 物理安装 epoch 与静态场景健康检查: 双机整体平移/支架松动可被拒扫, 阈值须真机 harness 标定。
- **M13.16 (P0 红)** ArUco site 求解器生产鲁棒性门: 权威标靶资产 (禁 marker_len 随请求传入)、角点精修/双解消歧/离群剔除/holdout/条件数, 任一失败不得保存 site。
- **M13.18 (P0 红)** B→A 精修对应质量门: 现有 pairs/RMS/Δt/ΔR 标量门不能排除对立面/低秩退化, 补双向对应/法向一致/覆盖/条件数/holdout。
- **M13.7** 现场重标 site 外参 (收 W −1.51% 残差): 依赖 M13.16 先行 + 用户确认空工位; 重标后必须重采背景。07-12 的临时豁免不计入验收。
- **M13.12** App/Web 同源生产重验真机门: 依赖 M13.15/16/18, 空工位重做 site → 重采背景 → 同车同 inspection 连扫 ≥3 次全一致。
- **M13.15 (P1)** 多工位/inspection/车辆资产闭环: 当前 App/Web 均不传 inspection_id, 工位仍是 worker 默认 IP 对; 终态 station_id→A/B+epoch 权威映射 + inspection asset 登记。
- **M13.17 (P1)** 逐车型法规规则与证据链 (当前只有 fail-closed"未判定")。
- **M8'-F3** 实时取景手动 RGB 点对求解 (自动 ArUco 不足时兜底; 单帧 2D 点对尺度欠约束, 须已知尺度靶点或多航向三角化)。
- **M9.2b** LWH <1% 对齐 (反汇编 bound_box/SOR 精确参数, 消 W/H 系统性 ~10mm); **M9.3b** 车型几何判型 (阻塞: 仅 100742 一个标注样本); **M9.5** 罐体/栏板/护栏专项 (无样本前不算完成); 真车 (非模型车) 的轴距/货箱参数重标。
- 收尾归置: ① `fix/laser-app-web-parity` 的历史提交与当前 master 已完成对齐，同题 finding 已去重; ② 07-01 App 瘦身、07-10 StationSwitcher、07-13 M13.10 回迁已随 a979415 落盘; ③ PointCloud3dView 漫游状态机死代码独立清理仍待处理 (07-01 遗留项), 顺带把 TODO M10 尾部"真机手感复核余项" (漫游走动/双指分流/B 框标注等) 按入口已撤事实出账; ④ 工位列表从客户端静态表升级服务端注册表下发 (07-10 TODO 终态, 与 M13.15 station_id 权威映射同向)。
