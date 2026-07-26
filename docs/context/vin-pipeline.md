# VIN 数码拓印流水线 — 历史上下文

> 最后更新: 2026-07-26 | 截至 commit: 36653a4 | 维护规则见 AGENTS.md「历史上下文维护」节

## 使命与当前状态

VIN 数码拓印 = 用 RS-D550 深度 + HLSD8 彩色两颗独立 USB 相机同步采集钢印 VIN, 经原厂标定做承印平面正射还原, 输出 4425×600 权威规范图, 交外部 OCR 识别出 17 位车架号 + 逐字符置信度。当前架构定型为: **手机只负责同步采集/原始落盘/上传/回显/触发 OCR; 标定解析、正射还原、17 字符刚性格架规范化、OCR 代理全在服务端 Go cvengine** (2026-06-18 用户拍板, 此后未变)。

截至 HEAD (36653a4, 2026-07-10): 服务端还原链 (原厂 BIN + 双轴 25px/mm + 4425×600) 与多角度固定坐标一致性 harness 已闭环 (M4.4/M4.5 验收指标全过、M4.7 契约落地、M4.8 ✅); M14.4 外部 OCR 接入与 M4.6 曝光同步/预览对齐/自动拍摄在**工作区推进至 2026-07-17+ 未 commit** (git status ~387 文件, 含 cvengine/scan3d/network 全链), 验证记录落在 TODO.md 与 08-summary。真机 (Android 16, RS-D550+HLSD8) 已连续多轮"自动对准→自动快门→还原→17 位 OCR"全通。剩余完成门只有两条: 曝光等效同步 ≤25ms 的物理证明、网页标定版本发布管理。

## 决策时间线

### 2026-05-04~05 服务端 cv-engine 地基先行 (M-S10)
App 尚无采集能力时, 服务端先落 cgo+OpenCV 的 cv-engine: `vin_character_compare` 首个真业务端点 (17250f2) → `vin_pipeline` 一站式端点 (整图→VMASK 分割→字符 mask→vin-ref 厂家字形库比对→verdict, f983358) → HMAC 验签 + worker + LLM 配额 (2e0d76d/9eb93e2)。这一层后来成为 VIN 还原上服务端 (M6.9.10) 的现成宿主。

### 2026-05-06 VIN 拓印定为三大业务主线之一 (3031de2)
方向调整: 砍手机主摄路线, 落定 iHawk 单设备 + 三维外廓 + VIN 拓印三主线。早期 M4.1-M4.3 规划的"端侧 native 拓印 (RANSAC 正射 1024×512)"由此排期, 后被服务端化取代。

### 2026-06-01 VINCreator APK 逆向 — 原厂参照系建立
从 vivo 拉取原厂 VINCreator (com.vin.uvc) 反编译: 它是 eYs3D 相机 App, 既是 M1.6/M6 UVC 驱动蓝本, 也是此后 VIN 还原逆向的 oracle 源。证据: `docs/agent-memory/finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md`、`tests/vincreator-apk/REVERSE-ENGINEERING.md`。

### 2026-06-02~03 M7 端侧业务链路拉通 (d2ea3e3, 提交 06-03)
`ScanCaptureScreen` 删硬编码 VinValue mock, 接 `CameraSource` 真帧 → `CVEngineApi.vinPipeline` 真 verdict。关键架构决策: cvengine 是内网服务, **端侧不持 HMAC 密钥**, 走 devserver `/cv/*` 反代 (JWT) + 服务端 `hmacauth.NewSigningTransport` 加签转发。证据: `docs/agent-memory/finding_scan_vin_wiring_2026-06-02.md`、TODO M7。

### 2026-06-10 发现 HLSD8 = VIN 真彩源 (M6.9 开线)
扫描机实为两颗独立 USB 相机 (RS-D550 深度 + HLSD8 13MP RGB), gomob 此前只接深度。HLSD8 4160×832 MJPEG 是原厂正射图的彩色源, 由此开 M6.9 双相机正射图线。相机驱动细节见兄弟文档 `docs/context/eys3d-rsd550-hlsd8.md`, 本篇只记 VIN 侧消费。

### 2026-06-18 端侧正射一日游 → 全量上服务端 (M6.9.5a→M6.9.10, 用户拍板)
上午 be8fd38 把端侧 `vinRectify` 桩换成 `native/vin/ortho_rectify` 真实现 (M6.9.5a); 当天用户两条拍板: ①还原算法按原厂逆向对齐、②算法全放服务端。原厂真管线在 `libcreator_jni.so::restoreImageFlow` (深度反投影→RANSAC 平面→tilt 门→摆正→metric 画布→单应 warp), 逐函数反汇编+对抗校验后端口进 Go cvengine `server/internal/cvengine/restore/` (44fa39e→97fcd7d 五连 commit: 端口/端到端回显/httptest 契约/OCR 接 vin_pipeline/yolo-obb 进 model-registry)。端侧 ortho_rectify 降级为即时预览近似。同时证伪: 4 月 `vin_rectify_demo.cpp` 是旧简化逆向稿非原厂源码; `picshadow` 不是去阴影是内容裁剪, `postProcessV3G` 不是调色是四角合法性裁剪。证据: `docs/agent-memory/finding_vin_rectify_serverside_calib_2026-06-18.md`、`docs/architecture/08-vin-rectify-design.md` §10。

### 2026-06-21 签名二值化真机订正 (6795cf3) → 2026-06-24 质量闸整体删除
真机暴露 `adaptiveThreshold(131,15)` 遇刻字偏亮整片反相 (白字黑底); 修为双边+背景除法平照+Otsu+极性归一, 并加"墨水占比>0.25 判废"质量闸。**06-24 订正: 质量闸误判废 43% (9/21) 真实好采集 (锐度/对比客观良好), "用输出后处理统计量反推采集质量"是因果倒置, 闸已删**; 坏采集判别只靠真链路信号 (OBB 检测失败/tilt 门)。证据: `docs/agent-memory/finding_vin_signature_binarize_realdevice_2026-06-21.md`。

### 2026-06-22 输出改彩色正射 + 字符端正 (4a5db82)
用户纠正: 原厂用户可见结果是**彩色**正射图, 黑白签名图只作内部分析/OCR 辅助。字符左右渐斜的几何根因: ①彩色内参误设 `fyc=2·fyd` (深度是竖直 binning 的 anamorphic 传感器 fy≈164/fx≈614, 彩色近方形像素, 应 `fyc=fxc`); ②平面拟合用固定中心 ROI 纳入背景污染法向, 改只在 OBB 区拟合。证据: `docs/agent-memory/finding_vin_ortho_color_upright_2026-06-22.md`。

### 2026-06-23 度量网格架构 + 深度尺度订正; atan 去畸变一日上线一日下线
用户报"仍有透视、不同角度不一样、要刻度尺"。系统诊断出两根因: ① render 用"OBB 四角单应钉角点+宽度归一"会掩盖真实几何且尺度随取景变 → 改**固定 mm/px 度量网格** (`Q=C+a·right+b·up` 逐点投影, 视角无关, 可叠 mm 刻度尺); ②深度绝对尺度错 (mode25 解码用全幅 fx 配 640 宽视差), 上经验因子 depthScale (0.19→0.0688→0.1116 真机尺子定标)。当天还上过"HLSD8 atan 广角去畸变"(逆向原厂 applyAtanDistortion 拟合参数), 在 cap_131/132 两张看似 3~7× 压平, **cap_133-139 多张复验推翻: 把端直钢牌弯成弧+图角黑楔, 净负已删**。随后用户判定所有图像美化后处理 (flattenRubbing 去阴影/锐化)"效果都不好, 去掉", 全部下线, 几何正确性改由真标定根治。教训入档: 几何修正必须多视角多张复验, 1-2 张数字会骗人。证据: `docs/agent-memory/finding_vin_metric_grid_depth_scale_2026-06-23.md` (含三段订正链)。

### 2026-06-23~24 ChArUco 自标定攻坚 (M6.9.11)
用户拍板"做标定"根治 `kc=2×depth 近似 + R=I,t=0 假设`。三轮迭代: HLSD8 预览帧糊→改全分辨率 MJPEG 抓帧; 7×5 板进不了 5:1 宽条→换 14×8/12mm/DICT_4X4; L' 有 IR 散斑检不出角点→native 写 0xE0 关 IR 投射器→角点立体法复活。32 张标出 HLSD8 K (fx1691, 与 2×depth 猜值 1229 偏 38%) + 外参 R 偏单位阵 4.16°/t 24.4mm (**旧 R=I/t=0 假设正是"内凹/视角相关"真因**), render 接真标定部署。标定采集页也按用户纠正从 VIN 拓印页搬到深度相机「Color↔Depth 标定」子页。证据: TODO M6.9.11、`tests/harness/vin_calib/`。

### 2026-07-11 原厂标定 BIN + 17 字符刚性格架 — 几何终态 (M4.5/M4.6)
决定性升级: 原厂标定 `VIN_BF301208.bin` (仓内副本 `tests/vincreator-apk/VIN_BF301208.bin`) 经原厂 native 逐字段确认是 2420B v3 完整双相机标定 (高分 RGB 内参+私有畸变+Euler 外参+mode25 深度 K+49.9893mm 基线), **推翻旧"只含深度参数/外参无效"结论** (f64 误扫成 f32 所致)。生产链改为: SHA-256 白名单加载原厂 BIN (按 `BF301208+202303111518+640×128+4160×832` 完整 rig 键) → 原厂反投影 `z=f·B/(raw·0.125)` → VINCHAR 拟合 **17 字符刚性格架 `cᵢ=o+(i-8)·p·u`**, 字符射线约束在深度承印平面内求等步长 3D 基线 → 双轴统一 25px/mm 一次 Remap 到 5000×678 工作图 → 中心裁切 4425×600。ChArUco 自标定与经验 depthScale 由此被原厂 BIN 生产键取代 (代码实证: `restore/render.go` 已无 calibK/depthScale/自标定残留, 生产仅 `calibration.go` SHA 白名单加载原厂 BIN)。四角度真 RGBD 验收: 4/4 严格 4425×600, 中心误差 X≤0.91px/Y≤1.92px, 节距 CV 0.174%, 固定坐标 Edge-F1 0.793 优于原厂 oracle 0.532。证据: `docs/agent-memory/finding_vin_fixed_character_grid_2026-07-11.md`、`.dev/vin_restore_consistency-factory-bf301208-v3/report.json`。

### 2026-07-14 5fps 双相机快门事务订正 (M4.6)
两颗 5fps 相机无共同硬触发。VINCreator 逆向+实机日志证明原厂快门是"跳 3 张彩色→至少收 3+3 帧→±100ms 内挑一对"的多帧事务; gomob 旧实现错误简化成"点击后第一组合格帧+等 500ms"。改为: 记录点击水位、整批候选取全局最小回调差 ≤100ms、失败最多重采 6 轮; 真机固定相位 53-55ms。**语义硬规: 这是"回调差"不是曝光同步**, 元数据写 `timestampKind=host_callback_monotonic`。证据: `docs/agent-memory/finding_vin_5fps_callback_pairing_2026-07-14.md`。

### 2026-07-10~17 M14.4 外部 OCR + M4.6 预览对齐/自动拍摄 (工作区, 未 commit)
- **M14.4 外部 OCR** (07-10 用户拍板): 识别调 `192.168.9.166:35000` 外部算法; Android 不直连, 正射 PNG 经 JWT 发 Gomob `POST /cv/ocr/v1/vin_recognize`, 服务端生成 `nanos+RSA-SHA1` 签名转调 `vin_detect`, 私钥不进 APK。单字符素材只取所选 item 的 `more[].origin_image_data` (真实 64×128 WebP), **不发 skip_image、不用整行 `vin_detect_image`**; 服务端解析为 `character_crops[]` 全量解码校验后下发 (M4.7)。老 `vin_pipeline` (VMASK 字形比对 verdict) 与新链**并存**: 服务端端点/worker/harness 仍在 (`handler.go` 仍挂 `POST /cv/ocr/v1/vin_pipeline`), 但端侧已不再调用 —— `CVEngineApi` 现仅 `vin_preview_calibration`/`vin_recognize`/`vin_restore` 三端点, VIN 识别主链已切外部 OCR。
- **预览空间对齐** (07-16): 新端点 `GET /cv/ocr/v1/vin_preview_calibration` 按完整 rig 下发只读原厂投影快照; App 深度预览逐像素 `disparity×8→3D→R/T→私有畸变→HLSD8 域` + 3×3 splat + z-buffer, 只改显示、落盘仍原始 RGBD。harness `vin_preview_alignment`: 覆盖 95.4%、Kotlin P95<30ms; 固定深度 2D 近似 P95 漂移 13.12px 证明不能用平移替代动态投影。
- **质量门+自动拍摄** (07-17): ROI 3×3 覆盖≥95% + 点支撑≥15% + 可靠中位距离≤400mm 三门; 达标后 5 帧/≥800ms/极差≤5mm 稳定观察自动快门, 还原成功 exactly-once 自动 OCR; 结果态冻结本次帧并按序释放双相机 lease。
- **稳定性 soak** (07-16): 连拍 7 次全首轮收齐, 3 轮退出重进内存无单调增长, 无 native fatal。
- **M4.8 使用页反馈收敛 ✅**: 双预览严格 5:1 全宽、设备入口迁 VIN 页顶栏、结果首层展示 VIN/置信度/17 张单字符图。
证据: TODO.md「M14.4」「M4.6」节逐验收单元记录、`docs/architecture/08-vin-rectify-design-summary.md`。

## 禁区与已证伪路线

- **别把 4 月旧简化逆向稿 `vin_rectify_demo.cpp` (VinRectifyDemo, 仓外历史稿, 本仓无此文件) 当原厂算法**: 真理源 = 反编 `libcreator_jni.so`。证据: finding_vin_rectify_serverside_calib_2026-06-18。
- **别用输出后处理统计量判废采集** (墨水占比质量闸一类): 因果倒置, 真机 43% 误判废; 任何判废门先用 harness 证明指标在好/坏样本可分离。证据: finding_vin_signature_binarize_realdevice_2026-06-21。
- **别再上 HLSD8 atan 去畸变 / 任何图像美化后处理**: atan 去畸变把端直钢牌弯成弧 (净负已删); flattenRubbing 去阴影/锐化被用户整体否掉, `Restore` 输出原始彩色正射+刻度尺。证据: finding_vin_metric_grid_depth_scale_2026-06-23。
- **别用钉角点单应+宽度归一 render, 别做输出后二次 OBB/shear/非等比拉伸/ECC/评估前逐图配准**: 会掩盖真实几何、破坏相似变换等变性; 规范化自由度只允许平移+旋转+统一 25px/mm。证据: finding_vin_metric_grid_depth_scale / finding_vin_fixed_character_grid、08-summary「几何不变量」。
- **别假设 `fyc=2·fyd`**: 深度传感器竖直 binning anamorphic, 彩色近方形像素; 双传感器 registration 别假设各轴同比例。证据: finding_vin_ortho_color_upright_2026-06-22。
- **别混用两个深度焦距**: Z 反算用原厂全幅 `disparity_focal=1229.20996`, `640×128` 的 `614.60498` 只用于 XY 反投影。深度输入是 raw u16 LE `disparity×8`, 不是 metric mm。证据: finding_vin_fixed_character_grid_2026-07-11。
- **别把快门做成"点击后第一组合格帧", 别称回调差为曝光同步**: 必须多帧事务+全局最小差; ≤100ms 回调门不等于曝光级同步 (那需要 PTS/SCR 或光学事件 ≤25ms)。证据: finding_vin_5fps_callback_pairing_2026-07-14。
- **几何修正 (去畸变/外参/尺度) 必须多视角多张复验再下结论**: 1-2 张的数字会因弯曲自抵消而骗人 (atan 事故的教训)。
- **OCR 契约**: 不发 `skip_image`; 不透传 `more`/`alpha_image_data`/整行图; 禁止拆整行图或用文本生成假切图; 数量/顺序/字符/分数必须与 VIN 严格一致, 损坏直接失败。证据: TODO M14.4、08-summary。
- **密钥/标定不进 APK**: cvengine HMAC 密钥只在服务端 (devserver 反代加签); 原厂标定 BIN 由服务端 SHA 白名单加载, 手机生产导航不暴露标定管理。证据: finding_scan_vin_wiring_2026-06-02、08-summary。
- **旧近似链已全部退役, 别复活**: 端侧 `vinOrthoRectify` 即时预览近似、`kc=2×depth`/`R=I,t=0` 假设、经验 depthScale (0.19/0.0688/0.1116/0.149 全链)、ChArUco 自标定 `calibration_2510DRK44C.json` (现仅存 `tests/harness/vin_calib/` 作历史资产) —— 终态统一为原厂 BF301208 BIN 完整 rig 键加载, 已由生产代码现状实证 (render.go 零自标定引用), 非仅推断。
- **VIN 页设备绑定**: 固定绑 RS-D550+HLSD8, 禁止按 USB 枚举瞬态回落 Berxel/内置彩色把错误相机数据送进原厂标定。证据: 08-summary。

## 关键资产指针

- `docs/architecture/08-vin-rectify-design.md` (+`-summary.md`) — 权威设计: 原厂逆向全量规格 (§10)、几何不变量、验收现状; 读 summary 即可接手。
- `server/internal/cvengine/restore/` — 生产还原核: anchor.go (17 字符格架) / render.go / plane.go / obb.go / calibration.go (原厂 BIN parser) / factory_projection.go / preview_calibration.go。
- `server/internal/cvengine/handler.go` + `vin_restore_http_test.go` / `vin_preview_calibration_http_test.go` — HTTP 契约与真数据回归。
- `feature/scan3d/.../VinCaptureViewModel.kt`、`VinRgbdPairer.kt`、`VinPreviewProjector.kt`、`VinAutoCaptureGate.kt`、`VinPreviewGeometry.kt`、`ScanCaptureScreen.kt` — 端侧采集/配对/预览投影/自动快门/UI。
- `core/network/.../CVEngineApi.kt`、`dto/VinDto.kt`、`core/data/.../scan/VinRepository.kt` — 网络契约与仓储 (逐字符严格校验)。
- `native/vin/ortho_rectify.{h,cpp}` — 历史端侧正射实现 (已降级, 留档)。
- harness: `tests/harness/vin_restore_consistency/` (多角度固定坐标一致性, M4.4 权威门) / `vin_rgbd_pairing/` (快门事务) / `vin_preview_alignment/` (预览投影跨端一致) / `vin_calib/` (ChArUco 自标定, 历史) / `vin_restore/` (Python 参考实现) / `cv_vin_pipeline` / `cv_vin_compare` / `vin_restore_performance` / `worker_vin_pipeline`。
- 标定与数据: `tests/vincreator-apk/VIN_BF301208.bin` (原厂标定仓内副本, 2420B, SHA `1a87dc03...` 实测一致; 原宿主 `/root/WindowsR/` 已不存在, 生产容器只读挂载 `/var/lib/gomob/vin_calibration/`); `.dev/vin_restore_consistency-factory-bf301208-v3/report.json` (权威验收报告); `.dev/vin_captures/` (真机采集)。
- agent-memory: `finding_vin_rectify_serverside_calib_2026-06-18` / `finding_vin_signature_binarize_realdevice_2026-06-21` / `finding_vin_ortho_color_upright_2026-06-22` / `finding_vin_metric_grid_depth_scale_2026-06-23` / `finding_vin_fixed_character_grid_2026-07-11` / `finding_vin_5fps_callback_pairing_2026-07-14` / `finding_scan_vin_wiring_2026-06-02` / `finding_vincreator_eys3d_uvc_blueprint_2026-06-01`。
- 采集设备驱动 (mode25 真深度/HLSD8 全分辨率/IR 控制) 见 `docs/context/eys3d-rsd550-hlsd8.md`; cvengine 基建/model-registry 见 `docs/context/infra-server.md`。

## 未竟事项

- **M4.6 曝光同步物理证明**: 用 PTS/SCR 或同步光学事件建立曝光时刻映射, 证明曝光等效差 ≤25ms; 软件回调门 (≤100ms) 不能替代。禁止用模板对齐/非等比拉伸绕过。
- **M4.6 网页标定管理闭环**: VIN 远程采集、姿态/角点质量、交叉验证、审核、按完整 rig/profile 发布标定版本的网页端; 当前只能用原厂 BIN, 标定职责迁移未闭环。
- **M14.4 收尾销账**: TODO M14.4 仍挂"Android 16 真机复跑真实外部 OCR"; 但 M4.6 07-17 记录已有真机多轮 17 位 OCR 成功 (APK 装 `192.168.9.17:35987`), 该项疑似已被覆盖未销账, 接手先对齐 TODO 口径。
- **工作区落盘**: 07-10 之后的 M14.4/M4.6 大量改动仍未 commit (与 M14.5 UI 改动混在工作区, ~387 文件), 接手先看 `git status` 与 TODO 对应节再动。
- **M7 尾巴销账**: TODO M7 备注仍挂 "VIN catalog 车型选择客户端"; 实证端侧已无 `vehicle_model_id`/`vinPipeline` 任何引用, M14.4 契约只收 `image_binary`, 该尾巴已被契约演进吸收 (车型字形比对能力保留在服务端 vinref/vin_pipeline), 待 TODO 正式销账。
