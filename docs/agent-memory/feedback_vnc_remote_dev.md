# 用户全程 VNC 远程：所有 GUI 必须走 DISPLAY=:1

**适用范围**：模拟器、IDE、浏览器、任何窗口型工具。

## 根因

用户在这台 CentOS 9 工作机上通过 **TigerVNC** 在 `DISPLAY=:1`（rfb 端口 5901）远程操作。任何启动到其它 display（Xvfb :2 / :3、headless）的 GUI 用户都看不到。

参见 `finding_emulator_setup_2026-05-04.md` 关于 `-gpu host` + NVIDIA 的详细配置。本文件强调的是"为什么必须走 :1" — 因为这是用户的眼睛。

## 强约束

- Android emulator 启动用 `DISPLAY=:1` + `-gpu host` + `-accel on`（`./dev.sh emu-start` 已正确实现）。**不要**加 `-no-window`，**不要**临时起 Xvfb 把 emulator 推到那边。
- 如果发现 `pgrep -fa Xvfb` 有 `:2` / `:3` 在跑，那是其它任务（screen recording / batch render 等）的副产物，**不要**给它装 app、**不要**把 emulator 切到那个 display。
- `adb install` / `./dev.sh install` 走 adb，与 DISPLAY 无关；只要 emulator 已在 :1 跑，apk 就在用户视野内。同时跑多个 emulator 时用 `ADB_DEVICE` env 锁定 `:1` 的那个。
- UI 工作完成后告诉用户"app 已在 VNC 桌面 emulator 里启动"，必要时配 `./dev.sh shot <name>` 截图作为佐证（adb screencap 来自 framebuffer，与 emulator 是否前台无关，可作为 sanity check 反向证明 install 装对了设备）。

## 验证清单

每次启动 emulator 前后跑一次：

```bash
# 1. 确认 VNC 在跑
pgrep -fa 'Xvnc.*:1' >/dev/null && echo 'VNC ok' || echo 'VNC down — 用户看不到 GUI'

# 2. 确认 emulator 用 DISPLAY=:1
PID=$(pgrep -f 'qemu-system.*-avd')
[[ -n "$PID" ]] && tr '\0' '\n' < /proc/$PID/environ | grep -E '^DISPLAY=' \
  || echo 'emulator 未跑 / 没 DISPLAY env'

# 3. 别的 Xvfb 是否在抢戏
pgrep -fa 'Xvfb' | grep -v 'X11-unix'   # 仅作提示，可能是无关后台
```

## 反例

- ❌ "我把 emulator 起到 Xvfb :2 上跑得快又稳" — 用户看不到，相当于 headless 调试。
- ❌ "emulator 加 `-no-window` 截图就够了" — 没有交互的复核机会，对 UI 工作不够。
- ❌ "起两个 emulator 一个 :1 一个 :2 各装一份" — adb 多设备造成歧义，install 装到哪一个不确定。
