# P100R3 并发 color+depth = MIX 模式（NATIVE_REWRITE 真因 + definitive 配方）

## 结论

P100R3 硬件**支持并发 color+depth**（用户实证：原厂安卓 SDK 能同开）。我们 NATIVE_REWRITE 之前一开 color 就整机 0 帧，**真因不是设备极限、不是 URB 数量、不是 2510DRK44C 设备特定**，而是：**我们没让设备进 MIX 模式**——回放的是 SINGULAR master init，设备不协调 master 彩色(0x81) + companion 深度(0x82)，于是双流互斥。

进 **MIX 模式**（回放原厂 MIX 序列）后，host NATIVE_REWRITE 路径（与 Android 同 portable / 同资产 / 同序列）裸 `--color --depth` 实测 **color 1040~1086 帧 / depth 146~147 帧（metric 中心 298mm 有效率 1.0）/ RGBD 145~147 对 / keepalive 100/100 ok / 全程 0 错**。

## MIX 配方（usbmon 抓原厂 SDK，逐位还原，零猜测）

抓包 harness：`tests/harness/berxel_mix_trace/`（原厂 `setStreamFlagMode(MIX)+startStreams(COLOR|DEPTH)`）。设备地址：**addr57=master**（XU wIndex 0x0500），**addr5=companion**（0x0300）。

- **master MIX 序列 = 21 条**（`core/native-bridge/.../assets/berxel/iHawkP100R3_master_mix_init.json`），相对 SINGULAR 关键差异：
  - StreamFlagMode（BX reg 0x0030）线上值 = **`0x0000` 写两次**（不是 0x02！enum MIX=0x02 但 .so 编码进线是 0x00）。
  - 多一条 **`cmd0x0007=0x0001`**（夹在两次 StreamFlagMode 之间）。
  - COLOR OpenStream（640×400@30，`42580c00...90011e00`）在 StartUsbStream + HostTime 之后、StreamFlagMode 之前（中段）。
- **companion MIX 序列 = 8 条**（`iHawkP100R3_companion_mix_init.json`），相对 depth-only(7 条)仅：cmd#5 reg0x19 `01→04`（MIX 模式寄存器）+ 末尾多 `0102 00`。
- **UVC commit**（从 pcap 解，非猜）：master color = **fmt1 frame3 interval 333333（30fps）**；companion depth = **fmt1 frame2 interval 222222（45fps）**。

## How to apply

- enable_color/MIX 并发：master+companion **都用 MIX 资产**；depth-only/color-only 仍用 SINGULAR。选择逻辑已落 Kotlin `BerxelNativeStack.startDualNative`（按 `enableColor`）、host `gomob_berxel_host_sdk.cpp` `master_payload_path/companion_init_path`、`berxel_host_probe.cpp` 默认（color+depth 自动选 MIX 双资产）。
- color 档 = **640×400@30**（interval 333333），与 MIX 序列里的 OpenStream + UVC commit 三处一致。
- **不要**再 patch StreamFlagMode（旧 `p100r3_mix_flag_mode`/`patch_p100r3_master_stream_flag_mode` 已删，0x02 是反编译错推断）。`patch_p100r3_master_color_open_stream_payloads` 保留：就地重写 MIX 序列自带的 OpenStream，host 实测重写 == 原厂 hex（no-op，仅保证与 color_mode 一致）。
- HostTime（master #13）仍由 `refresh_master_time_sync_payloads` 实时改写。
- 回归验收：`./dev.sh harness berxel_mix_replay`（run.sh + analyze.py，可判定 ✅/⚠/❌）。camera 必须在服务器（NATIVE_REWRITE host 路径）。

## 订正了什么

- 推翻 TODO/旧结论 "master color 流本身与 companion depth 在 2510DRK44C 互斥 / 设备特定"——是 MIX 模式缺失，非设备特定。
- 推翻我自己反编译推断的 StreamFlagMode=0x02 + COLOR OpenStream insert——MIX 序列里已自带正确的（0x0000×2 + 中段 OpenStream）。

## Android 真机 PASS（2026-06-02，2510DRK44C 直插 OTG 无 hub）

`am broadcast -n io.gomob.scan.debug/io.gomob.scan.debug.DebugBerxelReceiver -a ...DEBUG_BERXEL_START --es stream dual --ez master_rgb true`（enableColor=true → MIX）实测：**depth_seq 稳增 17→272 / ir_skipped=1 / pairs=271 / valid=1.000 / center_median≈2335mm（metric）/ depth_err=0 / color_chunks=7031 color_err=0 / q_drops=0**，color MJPEG#1400 解码 640×400→rgb24，无 setup 失败 / 无掉线 / 无 error-71。**color+depth 并发 RGBD 在 Android 真机彻底打通**。

要点：
- MIX 模式 **keepalive 关闭**（ka_ok=0）——master color bulk 自维持主控，不再需 XU5 set_cur 心跳（旧 rc=-7 超时彻底退场）。
- depth marker 实测全 `0x0600`（demux 正确，见 [[finding_p100r3_depth_ir_interleaved_2026-05-29]] 状态行标记）。
- **测试时序坑**：首次装 APK 后第一次跑可能 depth_seq 卡在 1（USB 冷态未 settle / 丢广播残留），洁净重装 + 单条显式组件广播即稳；非代码问题。
- 广播必须**显式组件** `-n .../DebugBerxelReceiver -f 0x20`（MIUI 丢隐式后台广播）。

至此 host + Android 双端均 device-validated，真因（MIX 模式缺失）订正闭环。带电 hub 仅是低供电机型兜底，2510DRK44C 直插即通。

## M6.8b ④ 收尾：删冻结 legacy berxelDual*（2026-06-02）

depth+color 真机 PASS 后删除冻结回退。「conf/IR/16-long stats vs legacy 一致」门 **by construction 满足**：(A) 生产 `berxel_get_stats` 与 (B) legacy `berxelDualStats` 16 字段逐位同序同锁；(A) BerxelSessionAdapter 把 depth_mm/conf/IR/color/dump 全路由到同一 `berxel_snap_*`/`berxel_take_color`/`berxel_dump_depth` core（B 即从此 core 逐字拷贝）→ 运行时 A-vs-B 比对=比对同一份代码，冗余。删 native `berxel_dual_session_jni.cpp` Android-only `#ifdef` legacy 段（1339→1037 行）+ Kotlin `NativeBridge.kt` 8 个 `berxelDual*` external fun + 订正全部 stale 注释。`./dev.sh build`+`test`+host probe 编译全过；生产唯一路径 = `cameraOpenByFds → BerxelDriver`。（`./dev.sh ci` 有预存 lint 失败在 feature:message，源自 commit f7951b9，与本工作无关。）
