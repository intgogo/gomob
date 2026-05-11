# gomob-server

gomob 移动应用的后端服务（Go）。

## 子服务

| 二进制 | 端口 | 职责 |
|--------|------|------|
| `gomob-gateway` | HTTP `:8808` / UDP discovery `:18809` | 反代 + JWT 校验 + wss 升级（App 唯一入口） |
| `gomob-api` | `:50051` | 业务 CRUD（查验 / 车辆 / 智能预审 / 抽查复核） |
| `gomob-auth` | `:50052` | 注册（含审核流） / 登录 / 改密 / token |
| `gomob-asset` | `:50053` | 图片 / 3D 扫描 / PDF 上传下载 |
| `gomob-signaling` | `:50054` | 消息中心 + WebRTC 视频信令 |
| `gomob-worker` | `:50055` | 智能预审 AI / 缩略图 / PDF 生成（异步） |
| `gomob-devserver` | HTTP `:18808` / UDP discovery `:18809` | 开发模式单进程合体（仅本地） |

## 快速上手

```bash
./scripts/ensure-go.sh   # 校验 Go 1.23+
make up                  # 起 docker-compose（postgres + redis + minio + nats）
make migrate             # 跑数据库迁移
make build               # 编译所有二进制 → .dev/bin/
make run-devserver       # 启动开发模式合体进程
```

仓库根目录的 `./dev.sh server run` 是 App 联调推荐入口：它会先启动本地
LiveKit dev server（`ws://127.0.0.1:7880`, `devkey/secret`）、应用 PG
migrations，再启动 `gomob-devserver`。真机 / 模拟器测试消息中心时执行
`./dev.sh reverse`，会把设备侧 `127.0.0.1:8808` 和 `127.0.0.1:7880`
分别反向代理到宿主机 devserver 与 LiveKit。

详细架构见 `../docs/architecture/server/`：
- `00-server-overview.md` — 总览（部署形态 / 服务划分 / 协议）
- `01-go-project-layout.md` — Go 工程布局
- `02-api-contract.md` — API 契约（待写）
- `03-data-model.md` — 数据模型（待写）
