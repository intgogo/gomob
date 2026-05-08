---
name: 模拟器在本机的稳定启动配置
description: emulator 36.x 在本机需 DISPLAY=:1 + -gpu host，并禁 netsim/packet streamer 相关链路
type: finding
---

# 模拟器在本机的稳定启动配置（2026-05-08 修订）

## Why

本机 Android Emulator 36.5.11 / 36.6.7 在默认 `gomob_test` AVD 上会不稳定：

- `-gpu swiftshader*` 路径曾稳定触发 `qemu-system-x86_64*` SIGSEGV。
- `DISPLAY=:1 -gpu host` 仍是 VNC 可见和渲染稳定的基础。
- 2026-05-08 新故障点是 netsim / packet streamer / WebRTC：日志会出现 `Unable to connect to packet streamer`，宿主 crash 栈落在 `libandroid-webrtc.so`，或 QEMU 在约 40 秒后退出 / hang。
- 直接禁 `BluetoothEmulation` 会让 guest 的 `com.android.bluetooth` 在 `HciHalHidl` 初始化时 native crash，弹 `Bluetooth keeps stopping`。

gomob 当前模拟器验证不依赖蓝牙 / 虚拟 WiFi / modem，所以稳定优先：保留 VNC + host GPU，禁掉不稳定的 netsim 相关链路，并在 guest 内禁用蓝牙系统包。

## How to apply

默认用脚本：

```bash
./dev.sh emu-start
```

它会启动：

```bash
DISPLAY=:1 emulator -avd gomob_test \
  -no-audio -no-snapshot -no-boot-anim \
  -gpu host -accel on -port 5556 \
  -feature -VirtioWifi \
  -feature -Mac80211hwsimUserspaceManaged \
  -feature -ModemSimulator \
  -crash-report-mode never
```

脚本还会在 ADB 可用后执行：

```bash
adb -s emulator-5556 shell pm disable-user --user 0 com.android.bluetooth
adb -s emulator-5556 shell am force-stop com.android.bluetooth
```

等 boot：

```bash
until [ "$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 5
done
```

安装并启动 app：

```bash
ADB_DEVICE=emulator-5556 ./dev.sh run
ADB_DEVICE=emulator-5556 ./dev.sh shot app-started
```

## 关键避坑

- **不要**把 GUI 放到 Xvfb；用户通过 TigerVNC 看 `DISPLAY=:1`。
- **不要**只用 `pkill -x qemu-system-x86_64` 停 emulator；Linux `comm` 会截断成 `qemu-system-x86`，匹配不到。`./dev.sh emu-stop` 已改为精确匹配 `$ANDROID_HOME/emulator/qemu/.*/qemu-system`。
- 如果看到 `libandroid-webrtc.so` / packet streamer / netsim 相关崩溃，先确认 `dev.sh emu-start` 的三组 `-feature -...` 还在。
- 如果看到 `Bluetooth keeps stopping`，确认：

```bash
adb -s emulator-5556 shell pm list packages -d | grep com.android.bluetooth
```

没有输出就补：

```bash
adb -s emulator-5556 shell pm disable-user --user 0 com.android.bluetooth
```

## 已验证

2026-05-08 在 `gomob_test` / Android 14 default x86_64 / emulator 36.6.7 上验证：

- boot 成功，`sys.boot_completed=1`。
- QEMU 越过原先约 40 秒崩溃窗口，到 86 秒仍在线。
- `ADB_DEVICE=emulator-5556 ./dev.sh run` 成功安装并启动 `io.gomob.scan.debug/io.gomob.scan.MainActivity`。
- 截图留存：`.dev/screenshots/app-started-final.png`。
