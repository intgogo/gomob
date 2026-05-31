# scan_fusion_e2e — M3.14 云端多视角融合全链路端到端 harness

## 目的

验证 M3.14 **完整 Go worker 垂直切片**的端到端正确性(非仅算法核):
端侧 bundle → MinIO → DB 队列 → fusionworker → fusion_service `/fuse` → GLB → MinIO → NATS `scan.fusion_done`。

算法核本身(配准+PGO+TSDF+conf 加权)由 `tests/harness/scan_fusion` 验(干净 chamfer ~1.3mm、conf 降 96%);
本 harness 验**管线接线**:DB 队列领取、MinIO 读写、HTTP 调服务、GLB 落库、事件发布,并对端到端产出的 GLB 做几何复核。

## 链路与组件

| 环节 | 实现 |
|------|------|
| RgbdShot bundle 契约 | `server/fusion_service/rgbd_bundle.py`(zip:manifest.json + rgb_i.png + depth_i.u16 + conf_i.u8) |
| 融合 HTTP 服务 `/fuse` | `server/fusion_service/app.py`(收 bundle → `fusion_core.fuse` → trimesh 导 GLB) |
| DB 队列 `scan_fusion_jobs` | `server/migrations/0016_scan_fusion.up.sql` + `server/pkg/repo/scan_fusion.go`(Enqueue/ClaimNext/Complete/Fail) |
| Go worker | `server/internal/fusion/{client,worker}.go` + `server/cmd/fusionworker/main.go`(轮询领取→MinIO 拉→/fuse→GLB 存 MinIO→发 `scan.fusion_done`) |
| e2e 断言 | `server/internal/fusion/e2e_test.go`(`//go:build e2e_fusion`) |

## 跑法

```bash
bash tests/harness/scan_fusion_e2e/run.sh
```

`run.sh` 自动:① `./dev.sh server up` 起 pg/nats/minio(宿主端口段 15432/14222/19000)→ ② migrate up(应用 0016)
→ ③ `prepare.py` 合成 10 视角 RGBD 打 bundle → ④ 起 `fusion_service`(uvicorn :18092)→ ⑤ `go test -tags e2e_fusion`
上传 bundle 入 MinIO + 入队 + `worker.ProcessOne` + 断言 DB done / `scan.fusion_done` 事件 / GLB 落库
→ ⑥ `verify.py` 回读 GLB、对齐 GT、chamfer vs 观测面。需带 open3d/trimesh/fastapi 的 `.dev/fusion-venv`。

## 判定门

1. **DB 任务 done** — `scan_fusion_jobs` 状态 done,result_object_key/vertices/triangles/stats 落库。
2. **`scan.fusion_done` 事件** — session_key / result_object_key / vertices 与 DB 一致。
3. **GLB 有效且几何正确** — 回读顶点 ≥ 1000,端到端 GLB 对齐 GT 后 chamfer ≤ 5mm(实测 ~1.3mm)。

## 边界(诚实)

- 合成 RGBD(GT mesh raycast),无真实传感器畸变;真实 8 张 P100R3 RGBD 端到端待端侧采集/上传(M3.12/M3.13)。
- 入队由测试直接 `Enqueue`(模拟 asset upload 完成后的入队);端侧 upload→入队 的 API 接线属后续。
- GLB 仅顶点色;UV atlas 纹理烘焙待 M3.14 后续。
