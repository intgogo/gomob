# tests/harness/ — 自分析与自优化

> **强制规则**：重要且存在不确定性的模块必须建 harness。
> 详见 `docs/agent-memory/feedback_harness_mandatory.md`。

## 目录约定

每个 harness 一个子目录，名字使用 snake_case：

```
tests/harness/<name>/
├── run.sh              采样器 — 触发场景 → dump 数据到 .dev/<name>/
├── analyze.py          分析器 — 读 .dev/<name>/ → 输出 PASS/WARN/FAIL
├── README.md           harness 目标 / 触发条件 / 阈值说明
└── (可选) soak.sh      规模 ×10（CI 夜间）
└── (可选) stress.sh    规模 ×100（周跑）
```

## 退出码契约

`analyze.py` 必须输出可判定结论 + 退出码：

| 码 | 含义 |
|----|------|
| `0` | PASS — 全部指标在阈值内 |
| `1` | WARN — 指标可疑但未越界 |
| `2` | FAIL — 指标越界，提交前必须修 |

## 子档约定（沿袭 gogame ADR-0005）

规模 / 时长 / 种子统一通过环境变量覆盖。

- **smoke**（默认 `run.sh`）— 秒级本地，每次提交都跑
- **soak**（`soak.sh`）— 规模 ×10，CI 夜间跑
- **stress**（`stress.sh`）— 规模 ×100，周跑

子档脚本只设置 env var + `exec run.sh`，不复制 sim 代码。env var 命名 `<HARNESS_NAME>_<PARAM>`。

## 调用方式

```bash
./dev.sh harness <name>                       # 默认 smoke 档
OUTPUT_DIR=.dev/<name>-soak ./dev.sh harness <name>
```

## 已规划的 harness（M1 之后逐个建）

| 名字 | 触发 | 覆盖模块 |
|------|------|---------|
| `rgbd_sync` | 参数敏感 + 长时序 | RGBD 双流时间戳同步、丢帧率 |
| `berxel_depth_parity` | 参数敏感 + 硬件状态 | P100R3 host raw depth 与原厂 SDK 多帧对齐 |
| `fusion_quality` | 涌现行为 | 主从外参投影颜色回填、点云密度 |
| `recon_quality` | 涌现行为 + 参数敏感 | TSDF voxel size、Marching Cubes 面片质量 |
| `calibration_quality` | 参数敏感 | 双摄标定 reprojection error / 跨标定一致性 |
