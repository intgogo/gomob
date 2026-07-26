# gomob

Android 端 3D 扫描应用 — 把外接 Berxel iHawk 深度相机与手机主摄像头深度绑定，
做"双摄合一"的高精度移动 3D 扫描设备。

## 快速上手

```bash
./scripts/ensure-android-sdk.sh   # 检查并补装 SDK / NDK / CMake
./dev.sh doctor                   # 自检环境
./dev.sh up                       # 一键启动服务栈 + devserver + ADB 反向代理
./dev.sh build                    # 编译 debug APK
./dev.sh install                  # 安装到当前已连接设备
```

## 服务清单

### 开发入口

| 服务 | 默认地址 | 启动入口 | 职责 |
|------|----------|----------|------|
| `gomob-devserver` | HTTP `:18808` / UDP `:18809` | `./dev.sh up` 或 `./dev.sh server run` | 本地联调合体网关，挂载 auth / api / asset / signaling，并反代 cv-engine / laserworker |
| `laserstationweb` | HTTP `127.0.0.1:5177` | 设置 `GOMOB_LASER_STATION_PASSWORD` 后 `cd server && go run ./cmd/laserstationweb` | 3D 激光扫描工位管理台，登录后通过 `/gateway` 连接 devserver |

### Go 后端服务

| 服务 | 默认地址 | 职责 |
|------|----------|------|
| `gomob-gateway` | HTTP `:18808` / UDP `:18809` | App 唯一入口，JWT 校验、限流、反代、WebSocket 升级 |
| `gomob-api` | HTTP `:18080` | 查验、车辆、审核等业务 API 与参考库 BFF |
| `gomob-auth` | HTTP `:18082` | 注册、登录、改密、token 刷新 |
| `gomob-asset` | HTTP `:18083` | 图片、扫描资产、PDF、模型等对象上传下载 |
| `gomob-signaling` | HTTP / WS `:18084` | 消息中心、WebRTC 信令、扫描实时事件推送 |
| `gomob-worker` | health HTTP `:18085` | 异步任务消费者，智能预审、缩略图、PDF、CV 调用 |
| `gomob-device` | HTTP `:18086` | 设备注册、心跳、标定参数与租约管理 |
| `gomob-laserworker` | HTTP `:18087` | 双单元激光扫描采集、点云融合、MinIO 落盘、NATS 实时推点 |
| `gomob-cvengine` | HTTP `:18810` | VIN / 车型 / 字形等 CV 推理与图像还原 |
| `gomob-llmgateway` | HTTP `:18811` | LLM provider 适配、模板编排、配额与流式输出 |
| `gomob-shaperef` | HTTP `:18056` | 3D 外廓参考库 |
| `gomob-modelregistry` | HTTP `:18057` | AI 模型版本、灰度、激活与热更新事件 |
| `gomob-vinref` | HTTP `:18058` | VIN 字形参考库 |
| `gomob-catalog` | HTTP `:18059` | 车型主数据与缓存 |
| `gomob-admin` | HTTP `:19090` | 管理面 BFF，聚合 catalog / model-registry / vin-ref / shape-ref / device / LLM |
| `gomob-asrworker` | 无 HTTP 端口 | 语音转写任务消费者，调用 ASR 推理服务 |
| `gomob-fusionworker` | 无 HTTP 端口 | RGBD 融合任务消费者，调用 fusion_service 并发布完成事件 |

### Python 推理服务

| 服务 | 默认地址 | 职责 |
|------|----------|------|
| `asr_service` | HTTP `:18091` | FireRedASR2S 语音转写推理 |
| `fusion_service` | HTTP `:18092` | Open3D 多视角 RGBD 融合，输出 GLB |
| `sam_service` | HTTP `:18093` | HQ-SAM 高精度 2D mask 分割 |

### 基础设施服务

`./dev.sh server up` / `./dev.sh up` 会用 podman 启动本地基础设施：

| 服务 | 默认宿主地址 | 职责 |
|------|--------------|------|
| `gomob-pg` | `127.0.0.1:15432` | PostgreSQL 主数据库 |
| `gomob-redis` | `127.0.0.1:16379` | 缓存、限流、轻量状态 |
| `gomob-nats` | `127.0.0.1:14222` / monitor `:18222` | JetStream 事件总线 |
| `gomob-minio` | API `127.0.0.1:19000` / console `:19001` | 对象存储 |
| `gomob-livekit` | `:7880` / `:7881` / UDP `:7882` | 开发视频通话服务 |

## 文档入口

- `AGENTS.md` — 所有协作 Agent 的统一入口
- `CLAUDE.md` — Claude Code 工作时遵循的项目规范（含第一性原则、UI 验证规范等）
- `docs/architecture.md` — 架构总入口
- `TODO.md` — 当前迭代任务

## 技术栈

Kotlin + Jetpack Compose + NDK(C++17) + Hilt + Room + CameraX + Filament + Coroutines

工程结构参考 [Now in Android](https://github.com/android/nowinandroid) 多模块约定。
