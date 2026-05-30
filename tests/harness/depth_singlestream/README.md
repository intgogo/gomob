# depth_singlestream — M1.6.6 BerxelNativeStack BULK 持续流 harness

## 用途

验证 [BerxelNativeStack](../../../core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/BerxelNativeStack.kt)
能在真机上稳定从 companion 节点 (0x3558:0x1012) 持续拉 BULK depth 数据。

关键参数：**master XU 5 keepalive 重发间隔**（5/20/50/100/200ms）和 **counter 模式**
（固定 vs 单调递增 +0x36）。Linux SDK trace 显示 ~2ms 间隔 + 单调 counter，但 vivo OTG
扛不住高频，需要扫一组找 sweet spot。

## 触发链路

```
sweep.sh
  → adb broadcast SONIX_DEBUG_HARNESS_RUN --el kaMs <N> --el durMs <M> --ei masterN 20
  → SonixDebugScreen (Compose) 收广播
  → runNativeStackFrameTest(stack, usbManager, kaMs, durMs, masterN)
  → 跑 durMs ms 后写 logLines (页面) + assemblerStats (NativeStack)
sweep.sh
  → uiautomator dump 抓 page log
  → analyze.py 判定 OK/WARN/FAIL
```

## 前置条件

1. **测试机已 install + 用户走过 SonixDebugScreen 一次**（让 master USB 权限缓存住）。
   首次安装后必须手动：3D tab → 深度相机 → Sonix 调试。
2. **测试机当前在 Sonix 调试页**：sweep.sh 只发广播，不导航 UI。
3. **OTG 物理状态干净**：每跑 5 轮建议物理拔插一次。vivo Funtouch USB state machine
   会在反复 open/close 后退化，master 节点从 `usbManager.deviceList` 隐藏。

## 用法

```bash
# 默认 5/20/50/100/200ms 扫一轮，每轮 5s
ANDROID_SERIAL=adb-XXX ./tests/harness/depth_singlestream/sweep.sh

# 自定义参数
ANDROID_SERIAL=adb-XXX DUR_MS=10000 \
    ./tests/harness/depth_singlestream/sweep.sh 50 100

# 单组 + analyze
./tests/harness/depth_singlestream/run.sh
```

## 输出

- `.dev/depth_singlestream/sweep_summary.tsv` — 一行一组：
  `kaMs status duration_ms data_reads total_bytes frames first_err ka_count`
- `.dev/depth_singlestream/sweep_ka<N>.json` — analyze.py 单组判定
- `.dev/depth_singlestream/sweep_ka<N>.log.page` — 该轮 SonixDebugScreen 页面日志
- `.dev/depth_singlestream/sweep_ka<N>.log.logcat` — 该轮 gomob_native logcat

## 判定标准

`analyze.py`：

| status | 条件 |
|---|---|
| OK | frames ≥ 10 AND bytes ≥ 320KB AND firstErr ∈ {0, null, -1007 timeout} |
| WARN | frames ≥ 3 OR bytes ≥ 100KB（短暂跑通，但有 NO_DEVICE / 错误） |
| FAIL | frames = 0 AND bytes < 100KB |

## 当前已知 baseline（2026-05-27）

| kaMs | 模式 | 持续时间 | 字节 | 备注 |
|---|---|---|---|---|
| 50 | 固定 payload | ~3s | 393KB (24 reads) | 18:20 + 19:28 重现，最优 |
| 5  | counter +0x36 | <1s | 0 | vivo OTG 撑爆 |
| 20 | counter +0x36 | 多次 master 缺失，未跑通 | — | 状态退化 |

sweep 的目标：找到既稳又能维持 ≥10s 的组合。

## 排错

- `❌ master 0x0603:0x001f 未发现` → SonixDebugScreen 页面里 `usbManager.deviceList`
  看不到 master。物理重插 OTG，等系统对话框（vivo manifest filter 会自动 grant）。
- `❌ stack.start 失败: master openDeviceByFd 失败` → kernel UVC driver 抢了 master。
  `BerxelNativeStack` 会自动 `libusb_detach_kernel_driver`，但有些 vivo 状态下仍失败 —
  cold restart app 重新启 SDK 让 kernel state 复位。
- `frames=0 bytes=N (N>0)` → 拿到字节但 frame 不切。检查 `BerxelFrameAssembler.expectedFrameSize`
  是否跟实际 firmware 出帧字节数一致（当前默认 640×401×2=513280）。
