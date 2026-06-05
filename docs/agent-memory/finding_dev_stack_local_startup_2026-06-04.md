---
description: 本地 dev 全栈启动配方——devserver(:18808) 是手动起的非守护进程，挂了 App 全链 "unexpected end of stream"；附一键拉起 + 症状诊断。
node_type: memory
type: finding
created: 2026-06-04
---

# 本地 dev 全栈启动配方（devserver 易挂，附一键拉起）

App 的登录 / 扫描 / WS 全部经 **devserver(:18808)** 这个 dev all-in-one 网关（反代 laserworker、cvengine，桥接 NATS→WS）。它是**手动起的普通二进制**（不是 systemd/守护进程，`server/.dev/bin` 还会被 `make clean` 清空），机器重启 / 会话切换后**经常已经没了**。此时基础设施容器和 laserworker 往往还活着，**只有 devserver 死**，表现为 App 端 `java.io.IOException: unexpected end of stream on http://127.0.0.1:18808/...`（登录、扫描、WS 全失败），极易误判成"模拟器坏了 / 参数错了"。

**Why:** 排查这个花过整段时间——`adb logcat` 全是 18808 失败，`ss -ltnp` 看不到 :18808 监听，但 `curl :18087/healthz` laserworker 是好的、podman 容器也都在。根因永远先查 **devserver 进程在不在**。

## How to apply

**症状 → 诊断（按序）：**
1. App 报 `unexpected end of stream on …:18808` → `ss -ltnp | grep 18808`，没监听就是 devserver 死了（不是模拟器/参数）。
2. `curl -s 127.0.0.1:18808/healthz` 应回 `ok`；`:18087/healthz` 回 `{"ok":true}` 是 laserworker。
3. 基础设施：`podman ps` 应见 `gomob-pg/redis/nats/minio/livekit`（持久 named volume，45h+ 常驻，极少需重起；真要起 `cd server && make up`）。

**一键拉起 devserver（worktree 根目录执行）：**
```bash
cd <repo>            # 如 .worktrees/laser-scan
( cd server/cmd/devserver && go build -o /tmp/gomob-devserver . )   # 纯 Go，无 cgo
export GOMOB_DB_DSN="postgres://gomob:gomob_dev@127.0.0.1:15432/gomob?sslmode=disable"
export GOMOB_NATS_URL="nats://127.0.0.1:14222"
export GOMOB_REDIS_ADDR="127.0.0.1:16379"
export GOMOB_MINIO_ENDPOINT="127.0.0.1:19000"
export GOMOB_MINIO_ACCESS_KEY="gomob" GOMOB_MINIO_SECRET_KEY="gomob_dev_minio" GOMOB_MINIO_BUCKET="gomob-assets"
export GOMOB_LASERWORKER_TARGET="http://127.0.0.1:18087" GOMOB_LISTEN=":18808"
setsid /tmp/gomob-devserver < /dev/null > .dev/devserver.log 2>&1 & disown
curl -s 127.0.0.1:18808/healthz   # → ok
```
端口都是**非默认**（pg 15432 / nats 14222 / redis 16379 / minio 19000），不传 env 会连默认端口失败。devserver 启动会 **dev seed 重置登录账号 `shenhm` / `shenhm123`**（每次启动重置 hash，必定可登）。

**laserworker（cgo，真采集）**：现常驻 `/tmp/laserworker.new6`，挂了才重建——须 `-tags laser_cgo` 链 `liblidar_scan.a`+PCL（`cd server/cmd/laserworker && go build -tags laser_cgo -o /tmp/laserworker.newN .`，先备好 native 库）。运行 env 同上 DB/NATS/MinIO/Redis，外加 `GOMOB_LASER_UNIT_A_IP=192.168.9.101 GOMOB_LASER_UNIT_B_IP=192.168.9.102 GOMOB_LASER_SET_SCAN_ANGLES=false GOMOB_LASER_ALIGN=none`（`SET_SCAN_ANGLES=false` 是硬约束，见 [[finding_laser_scanner_integration_2026-06-03]] / TODO M9.7）。监听 :18087。

**模拟器侧（emulator-5556）**：起 App 前补反向端口
`adb -s emulator-5556 reverse tcp:18808 tcp:18808; reverse tcp:8808 tcp:18808; reverse tcp:7880 tcp:7880; reverse tcp:19000 tcp:19000`。
模拟器黑屏 / GPU 见 [[finding_emulator_setup_2026-05-04]]（`-gpu host`+`DISPLAY=:1`，gfxstream `Failed to find ColorBuffer` 冷重启即解）。
