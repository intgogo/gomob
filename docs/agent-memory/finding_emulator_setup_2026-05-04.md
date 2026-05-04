---
name: 模拟器在本机 (CentOS 9 + RTX 2080 Ti) 上的可工作配置
description: SwiftShader 路径 SIGSEGV; 必须用 -gpu host + DISPLAY=:1 走真 NVIDIA; KSP2 不能跟 Hilt 2.53.1 一起用
type: finding
---

# 模拟器在本机的可工作配置（2026-05-04）

## 结论

**Android 模拟器在本机能跑的唯一组合**：
- 系统镜像：`system-images;android-34;default;x86_64`
- AVD：`pixel_7` 模板
- 启动参数：**`-gpu host` + `DISPLAY=:1`**（关键！）
- 必须 `setsid` 启动以脱离父 shell，否则父 shell 退出会带走它

## 不能工作的配置

| 配置 | 现象 |
|------|------|
| `-gpu swiftshader_indirect` (-no-window) | qemu-system-x86_64-headless SIGSEGV，每次都崩 |
| `-gpu swiftshader` (-no-window) | 同上 SIGSEGV |
| `-gpu swiftshader_indirect` + Xvfb DISPLAY=:2 | qemu-system-x86_64 SIGSEGV，崩在 Vulkan/SwiftShader 渲染初始化 |

**可工作配置**：`-gpu host` + 真 NVIDIA DISPLAY=:1 — boot 完成时间 ~30s

## 一键启动命令

```bash
export ANDROID_HOME=/opt/android-sdk
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

DISPLAY=:1 setsid emulator -avd gomob_test \
    -no-window -no-audio -no-snapshot -no-boot-anim \
    -gpu host -accel on -port 5556 \
    < /dev/null > /root/lilw/gomob/.dev/emulator.log 2>&1 & disown
```

等 boot：

```bash
until [ "$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 5
done
```

## 关键避坑

- **不要** `pkill -f qemu-system` — 模式会匹配自己 bash 的命令行，把当前会话也杀了。用 `pgrep` 拿 PID 再 `kill <PID>` 或 `pkill -x qemu-system-x86_64-headless`。
- **不要** 配 `hw.gpu.mode=swiftshader_indirect` 在 AVD config.ini 里 — 我已经加过，没影响（命令行 `-gpu host` 覆盖）。但留着没坏。
- **screencap 路径**：`adb -s emulator-5556 exec-out screencap -p > out.png`（`exec-out` 不要变 `shell` —shell 会做行尾转换破坏 PNG 二进制）。

## 可调整

如果以后 SwiftShader 修了 / 想做无显示器 CI，可以再试 swiftshader_indirect。
当前 finding 写明本机 2026-05-04 状态，避免下次又走一遍同一段调试。
