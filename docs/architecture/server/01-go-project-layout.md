# Go 服务端工程布局（server/）

参考 [golang-standards/project-layout](https://github.com/golang-standards/project-layout) +
gogame 仓的内部约定（`cmd/` 启动入口 / `internal/` 私有 / `pkg/` 公共业务）。

## 目录结构

```
server/
├── go.mod                        模块名 io.gomob/server
├── go.sum
├── Makefile
├── README.md
│
├── cmd/                          每子目录一个 main 包，对应一个二进制
│   ├── gateway/                  网关
│   ├── api/                      业务 API
│   ├── auth/                     认证
│   ├── asset/                    资产
│   ├── signaling/                消息 + 视频信令（仅 wss 在线推送）
│   ├── worker/                   AI 预审 / 异步任务
│   ├── device/                   相机绑定 + 标定参数云同步
│   ├── modelregistry/            AI 模型版本元数据 / 灰度 / 回滚
│   ├── admin/                    管理后台 BFF（注册审核 / 用户管理 / 模型切换 / 参考库审核）
│   ├── catalog/                  车型档案库（vehicle-catalog）
│   ├── vinref/                   车驾号字形参考库
│   ├── shaperef/                 车型 3D 外廓参考库
│   ├── cvengine/                 CV 算法引擎（VIN 检测 / 比对，从 gosmart 迁移）
│   ├── llmgateway/               LLM 大模型网关（DeepSeek 起步）
│   └── devserver/                单进程跑全部（开发模式）
│
├── internal/                     私有（不可被 server/ 外部 import）
│   ├── auth/                     auth 服务实现
│   ├── api/                      api 服务实现
│   ├── asset/                    asset 服务实现
│   ├── signaling/                signaling 服务实现
│   ├── worker/                   worker 服务实现
│   ├── gateway/                  gateway 服务实现
│   ├── device/                   device 服务实现
│   ├── modelregistry/            model-registry 服务实现
│   ├── admin/                    admin BFF 实现
│   ├── catalog/                  vehicle-catalog 服务实现
│   ├── vinref/                   vin-ref 服务实现
│   ├── shaperef/                 shape-ref 服务实现
│   ├── cvengine/                 cv-engine 服务实现（含从 gosmart 迁移的 ivv 处理流水线）
│   └── llmgateway/               llm-gateway 服务实现（provider adapter / 模板编排 / 流式）
│
├── pkg/                          可复用业务模块（service-agnostic）
│   ├── ent/                      数据模型（Go struct，PG 映射）
│   ├── repo/                     仓储层（DB 访问）
│   ├── token/                    JWT 签发 / 校验
│   ├── rbac/                     角色权限
│   ├── audit/                    审计日志写入
│   ├── logger/                   结构化日志（zap）
│   ├── metric/                   Prometheus 指标
│   ├── trace/                    OpenTelemetry 接入
│   ├── pubsub/                   NATS / Redis 抽象
│   ├── storage/                  MinIO / OSS 抽象
│   ├── ws/                       WebSocket 帮手
│   └── webrtc/                   信令辅助
│
├── proto/                        Protobuf 定义（服务间 gRPC + 部分对外）
│   ├── auth.proto
│   ├── api.proto
│   ├── asset.proto
│   ├── signaling.proto
│   ├── device.proto
│   ├── modelregistry.proto
│   ├── admin.proto
│   ├── catalog.proto
│   ├── vinref.proto
│   ├── shaperef.proto
│   ├── cvengine.proto
│   ├── llmgateway.proto
│   └── common.proto              CommonHeader / Pagination 等
│
├── configs/                      YAML 配置 + dev/prod 区分
│   ├── gateway.yaml
│   ├── api.yaml
│   ├── auth.yaml
│   ├── asset.yaml
│   ├── signaling.yaml
│   ├── worker.yaml
│   ├── device.yaml
│   ├── modelregistry.yaml
│   ├── admin.yaml
│   ├── catalog.yaml
│   ├── vinref.yaml
│   ├── shaperef.yaml
│   ├── cvengine.yaml
│   ├── llmgateway.yaml
│   └── dev/
│       └── docker-compose.yml
│
├── migrations/                   golang-migrate SQL 迁移
│   ├── 0001_init.up.sql
│   └── 0001_init.down.sql
│
├── scripts/
│   ├── ensure-go.sh              校验 Go 1.23+
│   ├── proto-gen.sh              protoc 生成 .pb.go
│   ├── migrate.sh                跑 migrations
│   └── seed.sh                   种子数据（dev）
│
└── tests/
    ├── integration/              端到端集成测试
    └── harness/                  自分析（沿袭 gogame 范式）
```

## 命名约定

- 模块名：`io.gomob/server`
- 包路径：`io.gomob/server/internal/api`、`io.gomob/server/pkg/repo`
- 二进制：`gomob-<service>`（gomob-api / gomob-gateway / gomob-cvengine / gomob-llmgateway / ...）
- 端口（gRPC 仅内网；gateway 与 admin HTTP 走外 / 管理网；cv-engine HTTP 沿用 gosmart 兼容路径仅内网）：
  - **生产端口**：
    - gateway 8808（HTTPS，App 唯一暴露入口）
    - admin 9090（HTTPS / mTLS，仅内网管理网段访问；BFF）
    - cv-engine HTTP 8810（仅内网，兼容 `/cv/ocr/v1/*`）
    - llm-gateway HTTP 8811（仅内网，流式 SSE）
    - gRPC：api 50051 / auth 50052 / asset 50053 / signaling 50054 / worker 50055
    - gRPC：device 50056 / modelregistry 50057 / admin 50058
    - gRPC：catalog 50059 / vinref 50060 / shaperef 50061
    - gRPC：cvengine 50062 / llmgateway 50063
  - **开发端口**（默认；统一 18000 段，避免 8080 / 8808 等常用端口与其它项目冲突）：
    - gateway 18808 / api 18080 / auth 18082 / asset 18083
    - signaling 18084 / device 18086
    - cv-engine HTTP 18810 / llm-gateway HTTP 18811
    - 各服务由 `GOMOB_<SVC>_HTTP_ADDR` 环境变量覆盖；harness / dev.sh 已对齐 18000 段

## 与 App 仓库的关系

本目录在 `gomob/` 仓内（mono-repo）。**不**单独建 git 仓 — 因为 App 与服务端的
Protobuf 契约要原子地一起改一起提交，跨仓 PR 同步成本太高。

`gradle/` 设置忽略 `server/` 子目录（不被 Gradle 扫描）：
通过 `settings.gradle.kts` 的 `pluginManagement` / `dependencyResolutionManagement`
默认只扫描 `include(...)` 显式声明的模块，**不会**自动 include `server/`。

## 开发主入口

`./dev.sh server`（待加）：
- `./dev.sh server doctor` — 校验 Go 工具链 + docker-compose
- `./dev.sh server up` — 启动 docker-compose（postgres + redis + minio + nats）+ 跑 devserver
- `./dev.sh server down` — 停
- `./dev.sh server migrate` — 跑 migrations
- `./dev.sh server proto` — 生成 protobuf
- `./dev.sh server build` — 编译所有二进制到 server/.dev/bin/
