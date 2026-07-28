# eYs3D RS-D550 + HLSD8 双相机自研驱动 — 历史上下文

> 最后更新: 2026-07-28 | 截至 commit: 0f60fc0 | 维护规则见 AGENTS.md「历史上下文维护」节

## 使命与当前状态

本模块负责扫描机上的**第二套相机硬件**: Etron/eYs3D **RS-D550 深度模组**(0x3438:0x0206, 双 IR 目 + 中间 5MP RGB + IR 投射器, 深度由片上 ASIC 算) 与 **HLSD8 13MP RGB 相机**(0x0C45:0x6366, Image+/Sonix, 标准 UVC MJPEG, ~4160×832)。二者是**两颗物理独立的 USB 相机**(2026-06-10 才发现), 共同支撑 VIN 数码拓印的 RGBD 采集。目标最初是"零厂商 SDK 全自研 UVC 驱动"(host-first→Android), 经 Android bulk 取流攻坚后, **终态定为: 控制/协议层自研认知 + 取流引擎 native 直驱厂商 libUVCCamera C++ 类(零 Java 编排, M6.9.9)**。

当前状态（截至本轮提交）: **能用** — RS-D550 mode25 真立体深度(640×128, `DISPARITY_X8_U16` 视差×8 原始值) + L'(1280×256 MJPEG) + HLSD8 RGB 三路真机稳定 ~5fps, 深度有效率中位 51.8%(vendor DepthFilter 补洞后, 旧稀疏 7~23% 已改善); 生命周期竞态已修(2026-07-16, 3 轮启动/teardown 对称、连续 7 次 VIN 采集无崩溃)，并在 2026-07-28 修复离屏 `AImageReader` 窗口引用计数崩溃；VIN 页固定绑定双相机, 快门回调差 53~55ms。**不能用/未完**: 仅 arm64-v8a(v7a 已定论不补); 自研零厂商取流栈在 Android 撞 -EPROTO 硬墙未破(挂起保留); 双相机曝光级同步(≤25ms 物理证明)未闭环(M4.6)。

## 决策时间线

### 2026-06-01 host 验机 + 开流序列解码 + 自研 libusb 双流首光 (M6.1–M6.3c)
背景: gomob 此前只有 Berxel 一条相机线(见 docs/context/berxel-p100r3-native.md), 用户引入 eYs3D 扫描机。RHEL9 服务器 + 带电 hub 验机: RS-D550 = 标准 UVC 1.00 单设备三接口(IF0 VideoControl 含 Etron XU id=4 / IF1 BULK 0x81 = L+R 立体 / IF2 BULK 0x82 = ASIC 深度)。eSPDI 直调一次出流推翻旧"RHEL9 不兼容"猜测; usbmon 抓包解码完整开流序列(XU 写 + selector 0x0a 递增计数器握手, 旧 0xE0/0xE3 激活猜测作废); 自研 libusb 逐字回放实现 RGBD 双流首光(color 32 帧 + depth 16 帧, 0 drop), 沉淀 4 条取流硬约束(异步多 URB / 回调不做重活 / URB=maxPayload / **depth 只在 color 并发排空时才出流**) + FID 拆帧法。证据: `docs/agent-memory/finding_rsd550_open_sequence_decoded_2026-06-01.md`、TODO M6.1–M6.3c、`docs/architecture/13-eys3d-driver.md` §1–2。

### 2026-06-01 mode25 配方锁定: 深度垃圾 = 模式配错, 非设备坏 (M6.4/M6.5)
背景: 正确 arming 后 IF2 仍吐"列恒定竖条纹"。一度判"设备深度死", 后经 VINCreator APK 静态逆向订正: ROSIE4(PID 0x0206)/USB2 必须 **mode25 = videoMode 寄存器 0xF0 写 36(0x24) + color 1280×256 MJPEG@5 + depth 640×128@5**; 旧序列的 `20 f0 02`(14bit) 正是退化真因。同期提取出厂标定(矫正 fx'=1229.205 / B=49.98mm)与 ZD 表(`Z_mm=ZDtable[disp]`), 并证 **eSPDI 零 host 软件 stereo, 视差 100% 来自设备 ASIC**(内嵌 StereoBM/SGBM 是死代码)——推翻 M6 立项时"深度靠端侧软件重建"的默认假设, 软件 stereo 降为 fallback(`eys3d_stereo_depth`, 已落码单测)。证据: 同上 finding「终极订正」节、13-eys3d-driver §2bis、TODO M6.4。

### 2026-06-01 多相机抽象层落地, eYs3D 与 Berxel 并存 (M1.8/M6.6–M6.8b)
`native/camera/` 抽出 `IUvcDevice`/`ICameraDriver`/`ICameraSession` 厂商无关切面, eYs3D portable 全家(protocol/depth/router/session_core/driver)落码单测; JNI 统一入口 `cameraOpenByFds` 按 vid:pid 分发; Kotlin 侧 `CameraSource` 中性接口 + `Eys3dCameraService` + `CameraSourceProvider`(默认回落 Berxel 不退化)。Berxel 全量迁入同抽象并删 legacy(细节属兄弟模块)。证据: `docs/architecture/12-camera-abstraction.md` §1.8、TODO M6.6–M6.8b。

### 2026-06-01 VINCreator 逆向 = 本线蓝本 (关联 M1.6)
原厂 VIN App「VINCreator」(`com.vin.uvc`) 反编译确认其为 **eYs3D/Etron 系(非 Berxel)**, fd 注入/权限模型/开流原语成为本线与 Berxel UVC 重写共同蓝本; 此后每次撞墙都以"VINCreator 在同机能跑"为 oracle 逐字 diff。证据: `docs/agent-memory/finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md`、`tests/vincreator-apk/REVERSE-ENGINEERING.md`。

### 2026-06-09 Android bringup: 一个月"bulk 0 字节"根除 → 又陷"2 帧停" (M6 frontier)
真机(小米 2510DRK44C)接通后连修 4 个 native bug(double close / reset 杀 fd / uvcvideo 独占需 force-claim / 计数器错位), 但 bulk 仍 0 字节。逐字复刻 eSPDI usbmon arming 全 153 条(补全 XU 能力枚举 / 完整 PROBE 协商 / COMMIT IF2 + trigger + CLEAR_HALT)后 bulk 首次出数据; 随后发现自研手卷 bulk 只吐 ~2 帧即停, 换 vendor libuvc(saki4510t 系, libusb100 后端) `uvc_start_streaming` 才连续 → **取流引擎必须是 libuvc 全套机制, 纯手动 submit 不触发设备开流**。当日 COLOR(YUYV 1280×480)上屏。证据: `docs/agent-memory/finding_eys3d_android_bringup_0bytes_2026-06-09.md` 续 1–11。

### 2026-06-10 双流通但深度退化; 硬件分解; ★发现 HLSD8 独立相机 (M6.9.1–M6.9.4)
IF2 手动异步 bulk 搭 libuvc 事件线程实现 color+depth 双流连续, 但 14bit 深度确认为"ASIC 退化图样"; 解析完整描述符 + VINCreator 默认表确认 USB2 真深度唯一路 = mode25。实物照片确认 RS-D550 板型 = 2 侧 IR 目 + 中间 RGB + IR 投射器 + 4 白光 LED。**同日重大订正: 4160×832 那路不是 eYs3D color, 是独立第二颗相机 HLSD8**(dumpsys usb 实证两条 UsbDevice; gomob 此前只接了深度)。当轮补齐 HLSD8 全链(native `Hlsd8Driver`/`Hlsd8UvcSession` 标准 UVC 自动选最大 MJPEG 帧 + Kotlin `Hlsd8CameraService` + `detectAuxRgb` 双相机路由 + UI 预览)+ 正射图几何 `native/vin/ortho_rectify`(host 测试 `tests/native_host/ortho_rectify_test` PASS)。证据: 同 bringup finding 续 12–30、`docs/agent-memory/finding_hlsd8_rgb_second_camera_2026-06-10.md`、TODO M6.9.1–9.4。

### 2026-06-11 arming 逐字对齐 + wedge 认知 + IF1 保活铁律定型 (M6.9.6/M6.9.7)
发现 arming 的 956 笔 flash 读是 **counter 门控握手**(selector 0x0a 滚动 nonce), 改 live-counter 全回放 2049 条后 0 fail, 控制序列 + bulk 参数与 VINCreator 逐字相同仍 0 深度 → 确认是**设备 wedge**(反复冷启/claim 把深度引擎搞挂, 对 VINCreator 一视同仁, 软件 reset 更糟, 唯物理重插可解)。续 33 揪出错前提: "原厂只开 IF2"来自 libusb100-only 垫片**漏抓了走另一 libusb 的 IF1 彩色**——VINCreator 实际双流, **IF1 彩色持续排空 = 深度 ASIC 保活命脉**。证据: 同 finding 续 31–33、TODO M6.9.6/9.7。

### 2026-06-11→15 vendor 栈移植三部曲 → 全 Java ApcCamera 出 metric 深度 (M6.9.8-streaming)
libuvc_lusb100(重编 pupil+libusb100)双流被真机证伪(连彩色都排不空, 续 34/06-11 锁定移植路线; 实施 06-14→15)后, 转移植 VINCreator vendor 栈: 先 native-direct dlopen libUVCCamera(修 connect 参数序/离屏窗/setVideoMode(36)/回调全局引用/彩色高带宽解锁 status=2), 深度流通但内容退化且 e0/e2 写不进; 用户拍板改**全 Java ApcCamera 路径**(植入 `com.esp.android.usb.camera.core.*` 18 类 + dlopen 手调 JNI_OnLoad 防 libusb 遮蔽), 真机出稳定 metric 深度(disp 低 11 位查 ZD 表→mm)。证据: 同 finding 续 34–44、TODO M6.9.8-streaming。

### 2026-06-15 ★mode25 真深度突破: 彩色必须 1280×256 MJPEG (M6.9.8)
续 44 的"真深度"被证伪——实为 **2 行复制罐头帧**(center 恒 459mm 跨 session 一模一样)。根因: 彩色用 2560×960 全分辨率让传感器进错模式, 深度 ASIC 拿错输入吐固定帧; 改 **mode25 原生 1280×256 MJPEG(传感器 binned)** 后 ASIC 真算立体视差(近物 disp≥200、随场景/帧间变化)。配套缺一不通(用户关键线索): color 暖机(首帧+4s)、**补光灯在 HLSD8 模块上**(先 acquire rgbSource 才点灯)、IR 在彩色流后设(e0=3/e2=6, 续 46 订正: e0/e2 是 IR 投射器非 depth-init)、AE=8、keep-surface; HLSD8 压最小档避免抢带宽。此时真深度但稀疏(7~23% 有效)。证据: 同 finding 续 45–47、auto-memory `finding_eys3d_mode25_real_depth_java_path_2026-06-15.md`、TODO M6.9.8。

### 2026-06-15→16 零厂商自研栈冲刺撞 -EPROTO 硬墙 (P1b, 挂起)
用户要求"完全脱离原厂库", 建成 `libuvc_gomob`(定制 libuvc + stock libusb-1.0, checked-in 源码)端到端跑通协商/arming(live-counter 2016 ok), 但 RS-D550 mode25 bulk 恒 -EPROTO 0 字节; 2026-06-16 末两后端对照(stock libusb vs saki libusb100 同 -EPROTO; 同栈跑 HLSD8 正常)证 **libusb 后端与自研 libuvc 都不是根因, 真根因 = mode25 起流/arming 序列复刻仍不全**, 且手头无一份"working mode25"usbmon 可逐字 diff。证据: `docs/agent-memory/finding_eys3d_zero_vendor_independence_2026-06-15.md`。

### 2026-06-17 ★终态生产路径: native 直驱厂商 C++ 引擎, 零 Java 编排 (M6.9.9, commit 5f7e574)
按用户架构定调"硬件交互层可由厂商提供", 放弃破 -EPROTO: dlopen `libUVCCamera.so` 直调 `UVCCamera/UVCPreview/FrameGrabber` C++ 类(over-alloc ctor + mangled 符号 + 校验 ABI offset 后 repoint FrameGrabber 纯 C 回调), 帧路径零 JNI, 仅留 Java 拿 fd + setVM。真机三路(L'+深度+HLSD8)全渲染 ~4-7fps; Java `Eys3dApcCamera.kt` 退役删除; vendor DepthFilter 使 valid 升至 50~78%。ABI 纪律(RTLD_LOCAL 隔离 libusb100 / 不跨 .so 构造 __ndk1 对象)成文。证据: commit 5f7e574、finding_eys3d_zero_vendor_independence「2026-06-17 突破」、13-eys3d-driver §3bis、TODO M6.9.9。

### 2026-06-18→24 双相机服务 VIN 主线 + HLSD8↔depth 真标定 (M6.9.5a/5b/10/11)
端侧正射拓印(M6.9.5a)后, 用户拍板 VIN 还原全量上服务端(端侧只拍+存+传, M6.9.10); ChArUco 标定(关 IR 让 L' 无散斑复活角点立体法)标出 HLSD8 真内参(fx≈1691, 2×depth 近似偏 38%)与外参 R 4.16°/t 24.4mm——**旧 R=I,t=0 假设正是"内凹/视角相关"真因**; HLSD8 atan 去畸变上线后被多角度复验证伪为净负, 回退删除(教训: 几何修正必须多视角多张复验)。还原/OCR/标定发布细节属 docs/context/vin-pipeline.md, 本模块承接的是双相机采集面(全分辨率 MJPEG 抓帧、`setIrProjector` 控制、标定采集页)。证据: TODO M6.9.5a/5b/10/11、`tests/harness/vin_calib/calibration_2510DRK44C.json`。

### 2026-07-14 深度单位订正: FrameGrabber 回调 = raw disparity×8, 非 metric mm
原厂 BIN 的 `depth_data_type=1` 与固定向量证明 `z=f·B/(raw·0.125)`; 旧"1746mm"其实是视差值。端侧 `DepthFrame.sampleFormat=DISPARITY_X8_U16` 明示透传, VIN 上传保留原始值, 服务端按原厂标定恢复毫米; **禁止上传前套未知 ZD LUT**。同期内参裁剪订正: mode25 = 1280×960 全幅竖向中心裁 1280×256 带再 0.5 缩放, 640×128 档 `fx=fy=614.60498, cx=324, cy=65.4325`(旧各向异性 fy≈163.9 撤销)。证据: finding_eys3d_zero_vendor_independence 踩坑 6 + 收口订正、13-eys3d-driver §内参。

### 2026-07-16 生命周期竞态修复 + 稳定性收口
厂商 `FrameGrabber::Open()` 异步起 worker, 旧代码返回即查 `isStarted` 误判失败提前 teardown → 厂商线程触碰已析构 mutex native abort。修法 = 启动屏障(started/failed/超时三态) + Close 完成门/join 先于对象释放 + callback context 覆盖全部回调 + Kotlin `Starting` 与终态分离。真机 3 轮启动/首帧/teardown 对称、7 次 VIN 采集无崩溃、PSS 无泄漏; harness `eys3d_vendor_cpp` PASS(双相机 ~5fps, 深度有效率中位 51.8%, 零 JNI)。证据: finding_eys3d_zero_vendor_independence「2026-07-16」节、13-eys3d-driver「生命周期不变量」。

### 2026-07-14→17 VIN 双相机运行时收口 (M4.6, 进行中)
VIN 页固定绑定 RS-D550+HLSD8(禁误回落 Berxel/内置彩色); 预览空间对齐(disparity×8→3D→R/T→畸变→HLSD8 投影, 覆盖 95%+); 取景质量门/40cm 上限/5 帧自动快门; 回调差 53~55ms 但**回调时间≠曝光时间**, ≤25ms 曝光等效物理证明未完。业务细节见 docs/context/vin-pipeline.md。证据: TODO「进行中: M4.6」。

### 2026-07-28 离屏窗口引用计数修复
VIN 端到端拍摄与结果回传成功后，Teardown 仍可能在 `~AImageReader → RefBase::decStrong` 崩溃。实测根因是厂商 `cam_dtor` 对交给 `cam_set_preview_display` 的离屏窗口多减一次强引用；`MakeOffscreenWindow` 现在显式 `ANativeWindow_acquire` 作为补偿，Teardown 解绑窗口后销毁相机，**绝不再配对 `release`**，否则补偿会被抵消。LOG-AN10 复测出现 `Teardown 完成 cbFrames=N` 且无 `signal 11`。完整引用计数账与禁区见 `docs/agent-memory/finding_eys3d_offscreen_window_refcount_2026-07-28.md`。

## 禁区与已证伪路线

- **禁: 全手动 bulk 取流**(任何 pump/缓冲数/start 序列/0x83 心跳组合)。设备只在 libuvc `uvc_start_streaming` 全套机制排空 IF1 时出帧, 手动路径 14bit=2 帧停、mode25=0 帧, 穷举证毕。证据: bringup finding 续 16/21/23/24 铁律总表。
- **禁: 用 2560×960(或任何全分辨率)当 mode25 彩色**。传感器进错模式 → 深度 ASIC 吐固定罐头帧(2 行复制/center 恒 459mm), 看似稳定实为死图。唯一正解 1280×256 MJPEG + 暖机/HLSD8 灯/IR 后设/AE/keep-surface 全套。证据: 续 47。
- **禁: 14bit(videoMode=0x02) 模式取深度**。ASIC 不真算视差, plumbing 再对内容也是退化图样。证据: 续 13/40、finding_rsd550 行 102。
- **禁: 继续在 Android 推自研零厂商取流栈出 mode25**(除非用户明确重启)。-EPROTO 硬墙未破且已证 libusb 后端/自研 libuvc 均非根因, 缺 working usbmon 参照; 生产已定调厂商 C++ 直驱。证据: finding_eys3d_zero_vendor_independence。
- **禁: 把 e0/e2 当 depth-ASIC 开关**。它们是 IR 投射器电流(cur=3/max=6), 写成功不解深度退化。证据: 续 46 订正续 41。
- **禁: 把 FrameGrabber 深度回调当 metric mm 用**。是 raw disparity×8; 需 metric 的消费者必须显式按标定转换。证据: 2026-07-14 订正。
- **禁: 对 wedge 设备做软件 reset 自愈**。Android 下 reset 重枚举杀 fd 且加剧 wedge; wedge(仍枚举但不产帧)唯物理重插, 整机掉总线是自动休眠(~15-20min)需重上电。证据: 续 18/31、finding_rsd550「会话健壮性」。
- **禁: 把 4160×832 当 eYs3D color / 把 HLSD8 当深度模组**。HLSD8 是独立 13MP RGB 相机; eYs3D 的 color/L' 是 1280×256。证据: finding_hlsd8。
- **禁: HLSD8 atan 去畸变进生产**。已证净负删除(把端直钢牌弯成弧); 当时依据的 `VIN_BF301215.bin` 是深度模组标定不含 HLSD8 (该判定仍成立; 勿与 07-11 确认含完整双相机标定的生产 BIN `VIN_BF301208.bin` 混淆, 见 docs/context/vin-pipeline.md)。证据: TODO M6.9.10 ⑪⑫。
- **禁: 补 v7a / 32 位路径**。厂商 C++ 仅 arm64, ABI offset 是 64 位布局, VINCreator v7a 是另一代 SDK 不可移植; 已加 64 位守门, 定论不补。证据: finding_eys3d_zero_vendor_independence 尾巴收口。
- **已证伪(勿再引)**: "RHEL9 环境不兼容"、"956 条 flash 是上传标定表"(实为读出厂标定)、"缺 0x83 心跳=2 帧停"、"IF2-only 开流"、"SET_INTERFACE 全程不发"(VINSHIM 漏抓)、"eYs3D 深度靠端侧软件重建"(ASIC 直出)、"CVideoDevice 可取流"(纯控制通道)、"libusb100 版本差异是 status=2 根因"。

## 关键资产指针

- `native/eys3d/android/eys3d_vendor_cpp_session.{h,cpp}` + `vendor_uvc_abi.h` — 生产路径: 直驱厂商 C++ 引擎, ABI 契约与 trampoline。
- `native/eys3d/android/` 其余(fd_session/pupil_session/mode25_libuvc_session/clean_arming_blob) — 自研攻坚遗留路径, 挂起非生产。
- `native/eys3d/portable/` — protocol(XU 原语)/depth(ZD 表)/depth_router/session_core/stereo_depth(软件 fallback), 全单测。
- `native/eys3d/host/` — host 会话/replay_stream(proven 开流序列)/stream_loop; `native/camera/` — 厂商无关抽象层。
- `native/hlsd8/hlsd8_uvc_session.{h,cpp}` — HLSD8 标准 UVC color-only 会话。
- `core/native-bridge/.../camera/` — `Eys3dCameraService.kt`/`Hlsd8CameraService.kt`/`CameraSource.kt`/`CameraSourceProvider.kt`/`CameraDetection.kt`/`CameraStack.kt` 双相机 Kotlin 编排。
- `third_party/eys3d-vendor/lib/` — RS-D550 厂商 C++ 栈仍仅 arm64；HLSD8 标准 UVC 的 `libuvc1/libusb1001/libjpeg-turbo15001` 按 ABI 成组投放，原厂 `libusb100` SHA 见目录 README；生产禁止 `libusb100real`/VINSHIM。`third_party/libuvc-android/` 为自研 libuvc_gomob 源(checked-in)。
- `tests/harness/eys3d_vendor_cpp/` — 生产路径真机 harness(起流链/fps/valid/零 JNI); `tests/harness/eys3d_mode25/` — host 自研路径回归。
- `tests/harness/vin_calib/`(含 `calibration_2510DRK44C.json` 双相机标定)、`tests/harness/vin_preview_alignment/` — 双相机对齐 harness。
- `tests/vincreator-apk/` + `REVERSE-ENGINEERING.md` — 原厂 oracle APK 与逆向报告(.dev/vincreator-jadx 为反编译产物)。
- `docs/architecture/13-eys3d-driver.md`(本线权威架构, §3bis=生产路径)、`docs/architecture/12-camera-abstraction.md`(抽象层)。
- agent-memory: `finding_eys3d_android_bringup_0bytes_2026-06-09.md`(47 续攻坚全史, 本模块最重要单文档)、`finding_eys3d_zero_vendor_independence_2026-06-15.md`(自研栈+终态路径+生命周期)、`finding_rsd550_open_sequence_decoded_2026-06-01.md`、`finding_hlsd8_rgb_second_camera_2026-06-10.md`、`finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md`。
- 标定/表离线产物(gitignored, 服务器 .dev/): `.dev/eys3d-sdk/tables/`(ZD/rectify)、`.dev/eys3d-probe/rsd550_calib.json`、usbmon trace 群。

## 未竟事项

- **M6.5 per-device flash 内参/ZD 经 JNI 下发**: 终态单一标定真理源; 当前端侧内参硬编码(614.6 系), depthScale 曾长期经验值, 待设备 flash 直读注入。
- **M6.9.11 标定收尾**: 标定硬编码单机(2510DRK44C), 待 `device_calibrations` 按 device id 加载; square_mm 实测复核。
- **M4.6 曝光同步物理证明**(跨模块, 主责 vin-pipeline): 回调差 53~55ms ≠ 曝光差, 须补 PTS/SCR 或同步光学事件证曝光等效 ≤25ms; 网页按完整 rig/profile 发布标定版本。
- **深度 densify 持续优化**: 有效率中位 51.8%(vendor DepthFilter 后), 进一步散斑亮度/滤波/场景纹理优化随主线推进(M6.9.8 遗留)。
- **自研零厂商栈(P1b)**: -EPROTO 未破, 作在研路线挂起; 重启前置 = 抓一份真 working mode25 全量 usbmon 逐字 diff。
- **M6.9.7 形式遗留**: TODO 中未划掉但已被 M6.9.8/9.9 实质取代(wedge 认知保留有效), 收口时应清理条目。
- **eYs3D 攻坚遗留死码清理**: `eys3d_fd_session`/`pupil_session`/`mode25_libuvc_session`/`clean_arming_blob` 等非生产路径仍在树上, 归属 M11 类还债。
