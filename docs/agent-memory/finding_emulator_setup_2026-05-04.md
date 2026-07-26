---
name: 模拟器在本机的稳定启动配置
description: emulator 36.x 需 host + SkiaVK/Vulkan，禁原生交换链与 netsim
type: finding
---

# 模拟器在本机的稳定启动配置（2026-07-11 修订）

## Why

本机 Android Emulator 36.5.11 / 36.6.7 在默认 `gomob_test` AVD 上会不稳定：

- `-gpu swiftshader*` 路径会被宿主 SELinux 拒绝 `execheap`，随后 emulator 退出。
- 仅用 `DISPLAY=:1 -gpu host` 时，guest UI 会走 Skia GL/EGL，再经宿主 llvmpipe；App 与 SystemUI 会共同阻塞在 `gralloc → BufferQueue → EGL`，最终对同一输入事件 ANR。
- 稳定路径是 `-gpu host -systemui-renderer skiavk -feature Vulkan -feature -VulkanNativeSwapchain`：guest UI 明确走 Skia Vulkan，同时避开 Xvnc 下不能创建 `VkSwapchainKHR` 的原生交换链。
- 2026-05-08 新故障点是 netsim / packet streamer / WebRTC：日志会出现 `Unable to connect to packet streamer`，宿主 crash 栈落在 `libandroid-webrtc.so`，或 QEMU 在约 40 秒后退出 / hang。
- 直接禁 `BluetoothEmulation` 会让 guest 的 `com.android.bluetooth` 在 `HciHalHidl` 初始化时 native crash，弹 `Bluetooth keeps stopping`。

gomob 当前模拟器验证不依赖蓝牙 / 虚拟 WiFi / modem，所以稳定优先：保留 VNC + host gfxstream Vulkan，禁掉原生 Vulkan 交换链与不稳定的 netsim 相关链路，并在 guest 内禁用蓝牙系统包。

`gomob_test` 的 AVD 模板可能生成 `hw.keyboard = no`，此时 VNC / 宿主机键盘事件不会送入 Android，只能逐键点击屏幕软键盘。宿主键盘是否可输入由 AVD 冷启动配置决定，不能靠 `adb input text` 验证。

## How to apply

默认用脚本：

```bash
./dev.sh emu-start
```

脚本在创建和每次启动 AVD 前都会把 `gomob_test.avd/config.ini` 固化为：

```ini
hw.keyboard = yes
```

它会启动：

```bash
DISPLAY=:1 emulator -avd gomob_test \
  -no-audio -no-snapshot -no-boot-anim \
  -gpu host -systemui-renderer skiavk \
  -accel on -port 5556 \
  -feature Vulkan \
  -feature -VulkanNativeSwapchain \
  -feature -VirtioWifi \
  -feature -Mac80211hwsimUserspaceManaged \
  -feature -ModemSimulator \
  -crash-report-mode never
```

脚本还会在 ADB 可用后执行：

```bash
adb -s emulator-5556 shell pm disable-user --user 0 com.android.bluetooth
adb -s emulator-5556 shell am force-stop com.android.bluetooth
adb -s emulator-5556 shell settings put secure show_ime_with_hard_keyboard 1
```

这样 VNC 桌面的实体键盘可以直接输入，同时屏幕软键盘仍保留备用。修改 `hw.keyboard` 后必须冷停再启动模拟器才会生效。

等 boot：

```bash
until [ "$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 5
done
```

安装并启动 app：

```bash
ADB_DEVICE=emulator-5556 ./dev.sh run
adb -s emulator-5556 shell uiautomator dump /sdcard/gomob.xml
```

## 关键避坑

- **不要**把 GUI 放到 Xvfb；用户通过 TigerVNC 看 `DISPLAY=:1`。
- **不要**删除 `-systemui-renderer skiavk` 或 Vulkan 两个 feature 参数；裸 `-gpu host` 会退回 Skia GL/EGL 经 llvmpipe 的阻塞路径。
- **不要**把默认值改成 SwiftShader；本机 SELinux 会拒绝其可执行堆映射，无法作为稳定降级路径。
- **不要**只用 `pkill -x qemu-system-x86_64` 停 emulator；Linux `comm` 会截断成 `qemu-system-x86`，匹配不到。`./dev.sh emu-stop` 已改为精确匹配 `$ANDROID_HOME/emulator/qemu/.*/qemu-system`。
- 如果看到 `libandroid-webrtc.so` / packet streamer / netsim 相关崩溃，先确认 `dev.sh emu-start` 的 netsim 相关禁用参数还在。
- 如果看到 `Bluetooth keeps stopping`，确认：

```bash
adb -s emulator-5556 shell pm list packages -d | grep com.android.bluetooth
```

没有输出就补：

```bash
adb -s emulator-5556 shell pm disable-user --user 0 com.android.bluetooth
```
- 如果只能点击软键盘、宿主键盘没有输入，先确认 AVD 配置与 guest 设置：

```bash
grep '^hw.keyboard' ~/.android/avd/gomob_test.avd/config.ini
adb -s emulator-5556 shell settings get secure show_ime_with_hard_keyboard
```

  两项应分别为 `hw.keyboard = yes` 和 `1`；第一项变更后执行 `./dev.sh emu-stop && DISPLAY=:1 ./dev.sh emu-start`。

## 已验证

2026-05-08 在 `gomob_test` / Android 14 default x86_64 / emulator 36.6.7 上验证：

- boot 成功，`sys.boot_completed=1`。
- QEMU 越过原先约 40 秒崩溃窗口，到 86 秒仍在线。
- `ADB_DEVICE=emulator-5556 ./dev.sh run` 成功安装并启动 `io.gomob.scan.debug/io.gomob.scan.MainActivity`。
- 2026-07-11 冷启动验证宿主键盘直输与屏幕软键盘共存。
- 2026-07-11 A/B 验证：App 管线为 `Skia (Vulkan)`，GPU 4950ms 桶由数百帧降为 0，连续 5 次开关 IME 无 ANR；诊断原始数据在 `.dev/anr-diagnosis/`。
