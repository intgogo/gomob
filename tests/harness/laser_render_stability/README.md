# 车辆外廓模拟器渲染稳定性 harness

本 harness 冷启动已安装 App，进入「3D → 车辆外廓扫描」，只恢复服务端已有的 `latest` 完成任务。它不会点击「开始扫描」或「重新扫描」，也不会清除 App 数据、外参、区域或背景。

严格验收：

- 融合 Surface 已建立后，首次切到分镜并立即返回；能恢复到 3D 根页，所有已创建 Engine 在同一 owner 线程销毁，期间无 ANR 或主线程 ≥5 秒帧。
- 融合源点 `2,050,753`，显示上传 `262,144`；A/B 各显示 `65,536`。
- 点云尺寸文字始终为 `0`：既不出现 11 个锚定文字，也不出现顶部车长/宽/高徽章；结果卡仍精确列出 13 条真实测量：车长/宽/高、4 轴三段轴距、总轴距、前后悬和货箱长宽深，不得缺失、重复或多出其他项。
- 13 条测量结果的 uiautomator `bounds` 必须有正面积，且完整位于屏幕根节点 `bounds` 内；避免“数据在语义树里，但实际被裁出屏幕”。
- 开启尺寸叠加且处于融合视图时，必须恰有一个 `车辆外廓尺寸线框 <N> 条` 语义节点且 `N>0`；关闭尺寸叠加或进入分镜时该节点必须为 `0`，回到融合后恢复。
- 首次分镜后总共只创建 3 个 Engine，三路点云各上传一次。
- 融合/分镜切换 20 轮，融合点云连续拖动 30 秒，再静置 10 秒。
- 不出现 ANR、FATAL、OOM、`EGL_BAD_SURFACE`、abandoned BufferQueue 或主线程单帧 ≥5 秒。

前置条件：`emulator-5556` 在线、debug APK 已安装、host `:18808` 服务可恢复包含上述真值的最近完成任务。运行：

```bash
./dev.sh harness laser_render_stability
```

产物统一写入 `.dev/laser_render_stability/`，包括完整 logcat、各阶段 uiautomator XML、gfxinfo framestats、线程样本、循环时延和 `report.json`。App 最终保持在融合页面。

可覆盖的参数：

```bash
LASER_RENDER_STABILITY_SERIAL=emulator-5556 \
LASER_RENDER_STABILITY_CYCLES=20 \
LASER_RENDER_STABILITY_DRAG_SEC=30 \
LASER_RENDER_STABILITY_IDLE_SEC=10 \
OUTPUT_DIR=.dev/laser_render_stability-local \
./dev.sh harness laser_render_stability
```
