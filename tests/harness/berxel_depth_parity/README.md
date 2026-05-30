# berxel_depth_parity — P100R3 Linux host depth raw parity harness

## 用途

验证 `native/berxel/host` 自研 SDK 的 P100R3 `DEPTH` active raw depth 是否与原厂 Linux SDK 对齐。

这个 harness 使用原厂 `.so` 只作为 oracle，不进入自研 SDK 正式链路。它会显式复位设备 sparse/dense 状态，避免 dense 状态跨进程粘住所造成的误判。
oracle 采样器源码保存在 harness 目录内，编译产物写入 `.dev/berxel_depth_parity/bin/`。

## 流程

1. 编译 `tests/harness/berxel_depth_parity/vendor_hawk_depth_read.cpp`。
2. 原厂 SDK 执行 `temporal=1, spatial=1`，复位到 sparse。
3. 自研 SDK 默认 controls 采集 host active raw depth 多帧。
4. 再次复位 sparse。
5. 原厂 SDK 执行 `AE=1, confidence=3, temporal=0, spatial=0`，采集 dense oracle 多帧。
6. 对比 vendor/host 多帧，并输出 vendor 自身、host 自身相邻帧噪声基线。
7. 可选复位后跑 `--no-depth-controls`，确认可以复现 sparse。

## 用法

```bash
./dev.sh harness berxel_depth_parity
```

常用覆盖：

```bash
FRAMES=30 SKIP=15 HOST_DUR_MS=4500 ./dev.sh harness berxel_depth_parity
CHECK_NO_CONTROLS=0 ./dev.sh harness berxel_depth_parity
OUTPUT_DIR=.dev/berxel_depth_parity-static ./dev.sh harness berxel_depth_parity
SCENE_NAME=matte_plate SCENE_MATERIAL=matte SCENE_DISTANCE_MM=500 SCENE_ANGLE_DEG=0 \
    ./dev.sh harness berxel_depth_parity
```

多场景 sweep：

```bash
tests/harness/berxel_depth_parity/sweep.sh

SCENES_FILE=tests/harness/berxel_depth_parity/scenes.example.tsv \
FRAMES=30 SKIP=15 tests/harness/berxel_depth_parity/sweep.sh
```

`sweep.sh` 每个场景会停下来等待摆放标靶；设置 `AUTO_CONFIRM=1` 可跳过等待。

## 输出

- `.dev/berxel_depth_parity/analysis.json` — 机器可读判定。
- `.dev/berxel_depth_parity/summary.md` — 人读摘要。
- `.dev/berxel_depth_parity/scene.json` — 场景元数据，用于材质/角度 sweep。
- `.dev/berxel_depth_parity/analysis/active-vs-vendor/summary.md` — 多帧对比详情。
- `.dev/berxel_depth_parity/vendor-dense/` — 原厂 dense oracle raw。
- `.dev/berxel_depth_parity/host-default/` — 自研 SDK active raw。

直接运行 `tests/harness/berxel_depth_parity/run.sh` 时，默认会输出到带时间戳的
`.dev/berxel_depth_parity/<YYYYmmdd-HHMMSS>/`。

## 判定标准

| status | 条件 |
| --- | --- |
| OK | sparse 复位有效；host/vendor dense 有效率 >= 98%；Jaccard >= 0.97；host-vendor median abs diff 不高于相机自身噪声底 15mm 以上 |
| WARN | 主指标通过，但 `--no-depth-controls` 对照或复位 sanity 有轻微异常 |
| FAIL | sparse 复位失败、dense 有效率不足、有效区或深度尺度明显偏离 |

当前基线（2026-05-28）：

| 指标 | 数值 |
| --- | ---: |
| sparse reset | 0.129 / 0.128 |
| host default dense | 1.0000 |
| vendor dense | 0.9984 |
| host `--no-depth-controls` | 0.1581 |
| median depth delta | 1.50mm |
| host-vendor median abs diff | 37.25mm |
| vendor/host 自身相邻帧 median abs | 36.625mm / 36.625mm |
| median over noise | 0.625mm |

`analysis.json` 还会输出中心 ROI 的 median depth 和帧间抖动范围。材质/角度 sweep 时，
保持相机和标靶静止，每个姿态跑一次并用 `SCENE_*` 环境变量记录场景。
`sweep.sh` 会把每个场景的关键指标汇总到 `summary.tsv`。
