# gomob-server

gomob 移动应用的后端服务（Go）。

## 子服务

| 二进制 / 服务 | 默认端口 | 职责 |
|--------------|----------|------|
| `gomob-devserver` | HTTP `:18808` / UDP discovery `:18809` | 开发模式单进程合体，App 联调推荐入口 |
| `gomob-gateway` | HTTP `:18808` / UDP discovery `:18809` | 反代 + JWT 校验 + 限流 + wss 升级（App 唯一入口） |
| `gomob-api` | HTTP `:18080` | 业务 CRUD（查验 / 车辆 / 智能预审 / 抽查复核）与参考库 BFF |
| `gomob-auth` | HTTP `:18082` | 注册 / 登录 / 改密 / token |
| `gomob-asset` | HTTP `:18083` | 图片 / 3D 扫描 / PDF / 模型上传下载 |
| `gomob-signaling` | HTTP / WS `:18084` | 消息中心 + WebRTC 视频信令 + 扫描实时事件 |
| `gomob-device` | HTTP `:18086` | 设备注册 / 心跳 / 标定参数 / 租约 |
| `gomob-laserworker` | HTTP `:18087` | 双单元激光采集 / 点云融合 / MinIO 落盘 / NATS 实时推点 |
| `gomob-cvengine` | HTTP `:18810` | 车型、字形与通用 CV 推理；不再承载 VIN 拓印还原 |
| `gomob-llmgateway` | HTTP `:18811` | LLM provider 适配 / 模板编排 / 配额 / 流式输出 |
| `gomob-shaperef` | HTTP `:18056` | 3D 外廓参考库 |
| `gomob-modelregistry` | HTTP `:18057` | 模型版本 / 灰度 / 激活 / 热更新事件 |
| `gomob-vinref` | HTTP `:18058` | VIN 字形参考库 |
| `gomob-catalog` | HTTP `:18059` | 车型主数据 |
| `gomob-admin` | HTTP `:19090` | 管理面 BFF |
| `gomob-asrworker` | 无 HTTP 端口 | 语音转写任务消费者，调用 `asr_service` |
| `gomob-fusionworker` | 无 HTTP 端口 | RGBD 融合任务消费者，调用 `fusion_service` |
| `laserstationweb` | HTTP `127.0.0.1:5177` | 3D 激光扫描工位管理台；启动必须设置 `GOMOB_LASER_STATION_PASSWORD` |
| `asr_service` | HTTP `:18091` | FireRedASR2S 语音转写推理 |
| `fusion_service` | HTTP `:18092` | Open3D 多视角 RGBD 融合 |
| `sam_service` | HTTP `:18093` | HQ-SAM 高精度 mask 分割 |

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

VIN 拓印的 bundle、标定解析、正射还原与 OCR 已迁移到独立 `vin-rubbing-service`。
gomob 服务不再加载 VIN 算法私钥或原厂标定文件。

详细架构见 `../docs/architecture/server/`：
- `00-server-overview.md` — 总览（部署形态 / 服务划分 / 协议）
- `01-go-project-layout.md` — Go 工程布局
- `02-api-contract.md` — API 契约（待写）
- `03-data-model.md` — 数据模型（待写）
