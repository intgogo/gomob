# device_realtime_interaction

目标：用日志优先的方式覆盖 M5 实时消息与直播控制面交互。

本 harness 不做默认截图，输出集中在 `.dev/device_realtime_interaction/`：

- `results.jsonl`：主机侧模拟 `emulator-sim` 与 `phone-sim` 两端登录、WebSocket 双向发消息、REST 拉历史 / 标已读、离线重连补齐，以及直播控制面能力探测。
- `devices.jsonl` / `capabilities.json`：ADB 发现的模拟器 / 真实手机、`adb reverse tcp:8808` 状态、是否启动了已安装 App。
- `adb-*.log`：设备 logcat，默认抓 `gomob` / `gomob_native` / `OkHttp` / `AndroidRuntime`。
- `auth.log`、`api.log`、`gateway.log`、`signaling.log`：服务端日志。

## 运行

```bash
./dev.sh harness device_realtime_interaction
```

可选环境变量：

- `DEVICE_REALTIME_START_APP=0`：只采 ADB 设备信息，不启动已安装 App。
- `DEVICE_REALTIME_ATTACH_APP_TO_HARNESS=1`：把 App 默认端口 `8808` 临时映射到本 harness 的 gateway；默认保持 `8808 -> 18808`，避免打断正在跑的 devserver。
- `DEVICE_REALTIME_APP_REVERSE_PORT=18808`：指定 App 端 `8808` 反向映射到宿主机哪个端口。
- `ADB=/path/to/adb`：指定 adb。
- `OUTPUT_DIR=.dev/device_realtime_interaction-xxx`：切换输出目录。

## 判定

`analyze.py` 按三态退出：

- `0`：正常，消息与直播控制面能力均通过，且设备日志无致命异常。
- `1`：警告，常见于未连接真实手机、未连接模拟器、App 没有产生日志，或 LiveKit / live session 控制面尚未实现。
- `2`：异常，消息链路失败、REST 404/网络错误、App 崩溃、服务端 panic / error。

当前 M5.4 尚未完成时，`L1.media_room_create_capability` / `L2.live_session_list_capability` 返回 404 会被标为“警告 / 阻塞”，不会伪装成真实直播已通过。
