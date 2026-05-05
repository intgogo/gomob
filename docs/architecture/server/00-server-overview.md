# 服务端架构总览（gomob-server）

> 主语言 **Go**，仓内 mono-repo `server/` 子目录。
> 反推自 App 设计稿（`docs/architecture/06-product-features.md`）。

## 1. 部署形态

```
        ┌───────────────────────────────────────────────────┐
        │                  公网 / 内网                       │
        │  App ──HTTPS/wss──▶ Gateway (单一 IP:Port)         │
        │                       │                            │
        │                       ▼                            │
        │   ┌──────────────── 服务总线 (gRPC) ────────────┐ │
        │   │                                             │ │
        │   │  Auth  API  Asset  Signaling  Worker       │ │
        │   │  Device  Model-Registry  Admin              │ │
        │   │  Vehicle-Catalog  Vin-Ref  Shape-Ref        │ │
        │   │  CV-Engine  LLM-Gateway  ──▶ DeepSeek / ... │ │
        │   │   │     │     │      │       │              │ │
        │   └───┴─────┴─────┴──────┴───────┴──────────────┘ │
        │       │     │     │              │                │
        │   PostgreSQL  Redis  MinIO/OSS   GPU 节点          │
        └───────────────────────────────────────────────────┘
```

App 端**只配置一个**网关地址（`112.145.10.91:8808`，对应"我的→网络设置"）。
内部所有微服务通过 gRPC 互通，不直接对 App 暴露。

## 2. 服务划分（基于第一性原理：每个服务围绕一个**资源边界**）

| 服务 | 职责 | 技术栈 |
|------|------|--------|
| **gateway** | 反向代理、路由、限流、认证 token 校验、wss 握手升级 | Go + go-zero / 自研 |
| **api** | 业务 CRUD（用户/查验/车辆/智能预审/抽查复核） | Go + Echo/Chi + PostgreSQL |
| **auth** | 注册（含审核流） / 登录 / 改密码 / token 颁发 / 角色 | Go + JWT + bcrypt |
| **asset** | 图片 / 3D 扫描数据 / PDF 上传下载，元数据落库，对象存储指向 MinIO | Go + 内容寻址（CAS） |
| **signaling** | 消息中心 + WebRTC 视频通话信令（仅 wss 在线推送） | Go + WebSocket + Redis pub/sub |
| **worker** | 智能预审 AI（OBD/外观/合规/年份码校验）、缩略图、PDF 生成 | Go + 异步任务队列（Asynq/NATS） |
| **device** | Berxel 相机绑定、双摄标定参数版本化云同步、用户多设备列表与配对 | Go + PostgreSQL |
| **model-registry** | AI 模型版本元数据、active/灰度/回滚策略；二进制存 asset/MinIO，worker / cv-engine 启动/热更时拉取 active 版本 | Go + PostgreSQL（元数据） + asset 复用（二进制） |
| **admin** | 管理后台 BFF（注册审核、用户/组织管理、参考库审核、模型切换、审计聚合）。前端单独仓，BFF 在本仓 server/admin/ | Go + gRPC + HTTP |
| **vehicle-catalog** | 车型档案库主数据：厂商 / 车系 / 年款 / 动力 / 合规清单 / 外观特征字段。是 vin-ref / shape-ref / inspection 的引用主键 | Go + PostgreSQL |
| **vin-ref** | 车驾号厂家字形参考库：厂商打刻样本图 + 字符级标注 + 特征向量；cv-engine vin_compare 时按车型拉对照样本 | Go + PostgreSQL（元数据 / 标注） + asset 复用（图） |
| **shape-ref** | 车型 3D 外廓参考库：标准 mesh / 点云 + 元数据；端侧扫描结果与之比对 | Go + PostgreSQL（元数据） + asset 复用（GB 级二进制） |
| **cv-engine** | CV 算法服务（VIN 检测 / 识别 / 字形比对 / 拓印识别），从 gosmart 迁移；同步 RPC，worker / api 调它落结果 | Go + Gin + OpenCV + ONNX/CCV，可挂 GPU |
| **llm-gateway** | LLM 大模型网关：API key 托管 + 多供应商路由（DeepSeek 起步）+ prompt 模板版本化 + 流式响应 + 调用审计 | Go + provider-agnostic adapter |

**为何不"单体"：** 信令服务和 API 业务的并发模型完全不同（信令长连接 + 事件驱动；
API 短连接 + 同步 CRUD）。混在一起会让任何一边的容量规划被另一边污染。
**为何不更碎：** 不必要的微服务化会引入分布式事务复杂度。当前 14 个服务各自围绕一个独立资源边界（流量入口 / 业务主域 / 身份 / 资产 / 实时信令 / AI 推理 / 物理设备 / 模型生命周期 / 管理面 / 车型主数据 / 字形参考 / 3D 外廓参考 / CV 算法引擎 / LLM 网关），不重叠。

**device / model-registry / admin 三个服务的拆分理由（第一性）：**
- **device**：物理设备（相机序列号 / 标定参数）的生命周期与查验业务的生命周期完全不同 — 一台相机标定一次能用半年，一次查验只活几分钟。塞进 api 会让 api 同时背负"短生命周期工作流" + "长生命周期资产管理"两种心智。
- **model-registry**：AI 模型是**版本化的语义资产**（v1.2.0-rc1 / canary / production），与 asset 的"内容寻址二进制"是两层概念。元数据放独立服务，二进制复用 asset 存储 — 既不重造对象存储，也不污染 asset 的 CAS 语义。
- **admin**：审核流共享 `users` 表与 casbin 策略，跨仓部署会引入双向 proto 维护与 schema 漂移风险；BFF 留本仓，前端 Web 在独立仓，正好分离"代码协同 vs 部署独立"两个诉求。

**vehicle-catalog / vin-ref / shape-ref / cv-engine / llm-gateway 五个服务的拆分理由（第一性）：**
- **vehicle-catalog**：结构化主数据，写少读多 + 跨业务广引用 + 持续入库工作流（厂商车型不断扩充）。塞进 api 会让 api 既背事务工作流又背主数据治理；独立服务后能单独缓存（Redis）/ 单独审核入库。
- **vin-ref / shape-ref**：都是"持续积累的厂商参考库"，但二者的**数据形态、入库流程、消费者完全不同** — vin-ref 是 KB 级字形图 + 字符级标注，消费者是 cv-engine；shape-ref 是 GB 级 mesh，消费者是端侧 / 未来的 3D 比对模块。强行合并会让两套异质的入库 / 质检 / 检索逻辑挤在一个服务。两者都通过 `vehicle_model_id` 外键引用 vehicle-catalog，二进制复用 asset。
- **cv-engine**：CV 推理是 GPU/CPU 密集 + 大模型加载 + 启动慢的特殊部署形态，与其它 Go 微服务的容量规划完全不同；从 gosmart 迁移时只带线上服务代码（`apps/api/ivv` + `engine/*` 必要依赖），训练 `ml/` 留 gosmart 仓不动。沿用 `/cv/ocr/v1/vin_*` HTTP 路径以兼容已有客户端，并对内额外暴露 gRPC 给 worker / api。
- **llm-gateway**：LLM 是外部供应商代理 + key 托管 + 流式响应 + 多供应商路由，与自有 cv-engine（自有模型）和 worker（异步任务）完全不同的故障域 / 计费域 / 并发模型。接口设计为 provider-agnostic（`POST /v1/llm/chat`，provider 由 prompt 模板 / 路由策略决定），DeepSeek 是首个 provider 实现。

**为何不做离线推送（FCM / 厂商通道）：** M1 阶段不引入。signaling 只做 wss 在线推送，App 离线消息靠下次上线时拉取（GET /v1/messages?since=server_seq）。代价已知 — 来电场景在 App 杀后台时不可用，等 M2 单独引入 push 服务再补。

## 3. 数据存储

| 存储 | 用途 | 表 / Bucket 示例 |
|------|------|------------------|
| **PostgreSQL** | 主数据：用户 / 组织 / 车辆 / 查验记录 / 抽查复核结果 / 消息元数据 | `users`, `inspectors`, `stations`, `vehicles`, `inspections`, `inspection_assets`, `reviews`, `messages` |
| **Redis** | 在线状态、未读计数、信令 SDP 候选缓存、限流计数器 | `online:<inspector_id>`, `unread:<inspector_id>`, `signal:<call_id>` |
| **MinIO / OSS** | 二进制：图片 / 3D 扫描 / PDF / 视频片段 | `assets/inspection/{vin}/{frame_id}.jpg`, `assets/scan3d/{session_id}/{n}.bin` |
| **PostGIS（可选）** | 检测站地理坐标 / 工位空间索引 | 后期加 |

## 4. 协议

| 通路 | 协议 | 编码 |
|------|------|------|
| App ↔ Gateway | HTTPS (REST) + WebSocket (wss) | JSON over HTTP / WebRTC SDP |
| Gateway ↔ 内部服务 | gRPC | Protobuf |
| 服务 ↔ 服务 (异步) | NATS / Redis pub-sub | Protobuf |
| 信令 / 视频 | WebRTC (peer-to-peer) + STUN/TURN | RTP/SRTP |

**为何 REST 而不全 gRPC**：移动端走 HTTP/2 + JSON 是最稳的，Protobuf 客户端
绑定额外维护成本对单一移动端没意义。**服务间** 走 gRPC 拿 Protobuf 严格契约 +
低延迟即可。

## 5. 鉴权 / 授权

- 注册需后台审核：`POST /v1/auth/register` 写入 `users` 表 `status='pending'`，
  审核员（Web 后台）改 `status='active'` 后才能登录
- 登录返回 access token (JWT, 2h) + refresh token (7d, 持久化在 Redis)
- 角色：`inspector` / `supervisor` / `reviewer` / `admin`，RBAC 走 casbin
- 网关层校验 JWT，下游服务只信网关注入的 `X-Gomob-User-Id` / `X-Gomob-Roles` header

## 6. 智能预审（AI worker）

App 上传扫描数据 → asset 服务落对象存储 → 发 NATS 事件 → worker 取任务 →
跑模型推理（OBD / 外观 / 合规 / 年份码 4 个独立校验器） → 结果写 `inspections.preliminary_result`
→ 推 WebSocket 给查验员。

**模型推理放 worker**（不在 API 服务内联）：
- 推理时延高（几百 ms ~ 几秒），不阻塞 API 主路
- 易于横向扩容（加 worker 实例就行）
- 易于灰度（不同模型版本走不同队列）

worker 启动时通过 gRPC 向 **model-registry** 拉取每个模型当前 active 版本，
并订阅版本变更事件（NATS topic `model.version.activated`）实现热更。
模型二进制实际存储复用 **asset** 服务（CAS bucket `models/`），model-registry 只管元数据。

## 6.x 设备管理（device）

- 用户在 App 端"我的→设备"绑定 Berxel 相机：上传序列号 / 固件版本 / 默认外参 → 落 `devices` 表
- 双摄标定结果（内参 K_rgb / K_depth、外参 R/t、畸变系数）按 `device_id + calibration_version` 版本化
- App 端启动扫描前先 GET `/v1/devices/:id/calibration?version=latest`，本地 Room 缓存命中跳过下载
- 多设备场景：同一用户多台手机 + 多台相机的笛卡尔积绑定关系存 `device_bindings`
- 标定参数生命周期独立于查验业务（半年级 vs 分钟级），单列服务避免 api 心智混入

## 6.y 模型生命周期（model-registry）

- 元数据表 `models`：`name / version / status (draft|canary|active|archived) / asset_uri / sha256 / created_at`
- 灰度策略表 `model_routes`：`name / canary_pct / canary_user_filter`（按用户白名单 / 按检测站 ID）
- worker 拉取规则：`status=active` 默认；命中 `canary_user_filter` 的查验任务走 canary
- 回滚 = 把上一版本的 `status` 从 `archived` 改回 `active`，秒级生效
- admin 后台触发版本切换 → model-registry 写库 + 发 NATS 事件 → worker 热更

## 6.z 厂商参考库三件套（vehicle-catalog / vin-ref / shape-ref）

```
vehicle-catalog (结构化主数据)
        ▲
        │ vehicle_model_id 外键
        ├──────────────┐
        │              │
   vin-ref (字形)   shape-ref (3D)
        │              │
        ▼              ▼
   cv-engine.       端侧扫描比对 / 未来 3d-compare
   vin_compare
```

**vehicle-catalog**
- 表 `vehicle_models`：`make / series / year / engine_type / outline_features_json / compliance_check_list_json / created_at`
- 接口：GET 列表 / 详情 / 搜索 +（受限）POST 入库 / PATCH 修订
- 缓存：Redis 按 `vehicle_model_id` LRU；变更时 NATS 广播失效
- 入库流程：录入 → admin 审核 → published

**vin-ref**
- 表 `vin_glyph_samples`：`vehicle_model_id / batch_no / image_asset_uri / char_positions_json / feature_vector_uri / status`
- 二进制：图片 + 特征向量走 asset bucket `vin-refs/`
- 入库流程：上传打刻样本 → 标注字符位置（admin 后台） → 提取特征 → 审核 → published
- 消费方：cv-engine `/cv/ocr/v1/vin_compare` 时按 `vehicle_model_id` 拉对照样本

**shape-ref**
- 表 `vehicle_shapes`：`vehicle_model_id / mesh_asset_uri / point_count / coverage / qc_score / status`
- 二进制：mesh / 点云走 asset bucket `shapes/`，单文件 GB 级，建议大文件分片 + ETag 续传复用 asset 通道
- 入库流程：标准车型扫描 → 重建 → 质检（密度 / 完整性 / qc_score 阈值） → 审核 → published
- 消费方：端侧扫描完成后下载对照模型；未来加 3d-compare 服务做云端比对

## 6.app App 端通路策略（决策）

```
              ┌─────── gateway 8808 (HTTPS) ───────┐
   App ──────▶│                                   │
              │  /v1/auth/*       → auth          │
              │  /v1/inspections  → api           │
              │  /v1/reviews      → api           │
              │  /v1/messages     → api           │
              │  /v1/devices      → device        │
              │  /v1/catalog/*    → api (BFF 转发)│  ← 参考库三件套：api 包装
              │  /v1/llm/*        → llm-gateway   │  ← LLM 直达（流式）
              │  /cv/ocr/v1/*     → cv-engine     │  ← OCR 直达（实时性）
              │  /v1/ws           → signaling     │
              └───────────────────────────────────┘
                          │
                  内部 gRPC ↓
              api ──gRPC──▶ catalog / vinref / shaperef
              cv-engine ─gRPC▶ vin-ref / model-registry
              worker ──gRPC─▶ cv-engine / model-registry
```

**决策摘要：**
- **实时类**（cv-engine / llm-gateway）：gateway 路由直达，避免 api 中转引入额外延迟；LLM 流式 SSE 也由 gateway 直接 pipe 到客户端
- **参考库三件套**（vehicle-catalog / vin-ref / shape-ref）：HTTP 入口由 **api 服务** 承担（路径前缀 `/v1/catalog/*`），api 内部 gRPC 调对应服务。理由：参考库是查验业务的引用资料（不是独立交互对象），让 api 在 inspection 详情里一并返回引用 ID + 提供按 ID 拉详情的代理接口，App 只感知一致的 `/v1/catalog/*` 而无需关心后端服务拆分
- **api 不会变成肥 BFF**：参考库代理只是**只读 GET 透传**（无业务逻辑），写入路径全部走 admin（参考库入库审核）；api 不为 cv-engine / llm-gateway 做任何代理（实时类直达）

**鉴权策略：**
- 全局：gateway 校验 JWT，下游服务信任 `X-Gomob-User-Id` / `X-Gomob-Roles` header
- **cv-engine 双轨**：JWT 鉴用户身份 + 沿用 gosmart 时代的请求体验签机制（`appid + sign + ts + nonce`）防重放。两层都通过才能调用 OCR 接口（高价计算资源，单 JWT 不够）

**离线 / 缓存策略：**
- vehicle-catalog：**全走网，App 不本地缓存**（决策已定）。检测站现场弱网由网络层兜底重试；地下车库等极端弱网场景待 M2 评估
- 端侧 RGBD 帧 / 标定参数：本地 Room 缓存（端侧本来就要存）

## 6.w CV 算法引擎（cv-engine）

- **来源**：从 `/root/lilw/gosmart` 迁移线上服务部分（`apps/api/ivv` + `engine/*` 必要依赖 + `apps/apps.go` 路由）；训练 `ml/` 不迁，留 gosmart 仓继续迭代
- **接口**：
  - 对外 HTTP（沿用 gosmart 路径，兼容已有客户端）：`/cv/ocr/v1/vin_detect` `/vin_compare` `/vin_more_compare` `/vin_character_compare` `/vin_character_detect` `/vin_rubbing`
  - 对内 gRPC（worker / api 调）：同一组能力的 protobuf 包装
- **模型加载**：启动时调 model-registry 拉 active 版本 yolo 检测器 + 字形比对模型；订阅 NATS `model.version.activated` 热更
- **依赖外部数据**：`vin_compare` 流程需要按 `vehicle_model_id` 拉 vin-ref 对照样本（gRPC 调 vin-ref）
- **部署形态**：可挂 GPU；启动慢（模型加载几秒到几十秒），独立扩缩容；`/healthz` 仅在模型加载完成后返 200

## 6.v LLM 大模型网关（llm-gateway）

- **能力**：API key 集中托管 + 多供应商路由 + prompt 模板版本化 + 流式响应转发 + 调用审计
- **provider-agnostic 接口**：
  ```
  POST /v1/llm/chat              { template_id, vars, stream }
  GET  /v1/llm/templates         列出模板（版本化）
  POST /v1/llm/templates         上传 / 修订模板（admin 角色）
  ```
- **当前 provider**：DeepSeek（首个实现）；后续按需加 Claude / GPT，业务代码不改
- **路由策略**：模板可指定 `preferred_provider`，路由层加供应商可用性 / 限额 / 灰度比例判断
- **审计**：每次调用落 `llm_call_logs`：`user_id / template_id / provider / token_in / token_out / latency_ms / cost`
- **流式**：HTTP SSE 转发；网关侧不缓冲整个响应，背压感知
- **不放在 worker / cv-engine 的理由**：见上方 §2 第一性论证

## 7. 信令 / 视频通话

- App 通过 wss 长连接到 signaling 服务
- 主叫发起：`POST /v1/calls/{peer_id}/invite` → signaling 通过 wss 推 invite 到被叫
- 双方 SDP / ICE candidate 交换走 wss 转发
- 媒体走 WebRTC peer-to-peer（同检测站内网走局域网，跨网走 TURN）
- 通话记录元数据（开始/结束/时长）落 `call_logs` 表
- **离线兜底（M1）**：被叫不在 wss 在线时，invite 写 `pending_calls` 表 + 设 60s 过期；
  App 上线时 wss 握手返回未读消息 + 待接来电列表。**不接** FCM / 厂商推送通道（M2 再考虑）。

## 7.x 管理后台（admin）

- 本仓内 `server/admin/`，仅提供 gRPC + HTTP BFF；前端 Web 在独立仓
- 主要能力：
  - 注册审核：`pending` 用户列表 / 通过 / 驳回（同步改 `users.status`）
  - 用户与组织管理：检测站 / 工位 / 角色分配
  - 模型版本切换：调 model-registry 切 active / canary
  - 审计日志查询：跨服务 `audit_log` 聚合
- 共享 `users` 表与 casbin 策略，避免跨仓 schema 漂移；前后端通过同一仓 proto 演进

## 8. 网关 / 反向代理职责

- TLS 终止
- 路由（前缀分发到内部服务）
- 限流（token bucket，按 user_id 维度）
- WebSocket 升级
- 鉴权（统一 JWT 校验）
- 跨域（不需要，App 不是 Web）
- **不做业务**：不在网关写业务逻辑（避免成为单点变更瓶颈）

## 9. 配置与部署

- 配置：`configs/<service>.yaml` + 环境变量覆盖
- 部署：每服务一个二进制 + Dockerfile；docker-compose（开发） + k8s（生产）
- 可观测：Prometheus（metrics）+ Loki（日志）+ Tempo（trace），统一通过 OpenTelemetry SDK
- 健康检查：每服务 `/healthz` + `/readyz`

## 10. 与 App 的对接边界（API 清单摘要）

详见 `docs/architecture/server/01-api-contract.md`（待写）。本节给概览：

```
POST   /v1/auth/login                 用户名密码 → tokens
POST   /v1/auth/register              注册（待审核）
POST   /v1/auth/refresh               refresh → access
POST   /v1/auth/password              改密
GET    /v1/me                         我的信息
PATCH  /v1/me                         改个人信息
GET    /v1/stations/:id               检测站详情

GET    /v1/inspections                查验列表（分页 / 过滤 chip）
POST   /v1/inspections                创建查验
GET    /v1/inspections/:id            查验详情
PATCH  /v1/inspections/:id/result     更新预审结果
POST   /v1/inspections/:id/assets     上传图片 / 3D 扫描分片

GET    /v1/reviews                    抽查复核列表
POST   /v1/reviews/:id/decision       提交复核结果（正确 / 错误 / 跳过）

GET    /v1/messages                   消息列表
GET    /v1/conversations/:peer_id/messages   单聊历史
WS     /v1/ws                         消息 / 信令双向通道（含上线时待接来电下发）

GET    /v1/devices                    我的设备列表
POST   /v1/devices                    绑定新相机（序列号 + 固件版本）
GET    /v1/devices/:id/calibration    拉取最新标定参数（带 version）
POST   /v1/devices/:id/calibration    上报新一轮标定结果

GET    /v1/catalog/vehicles           车型档案查询（make/series/year 过滤）
GET    /v1/catalog/vehicles/:id       车型档案详情
GET    /v1/catalog/vehicles/:id/vin-refs    该车型可用的字形参考样本
GET    /v1/catalog/vehicles/:id/shape       该车型 3D 外廓资产引用

POST   /cv/ocr/v1/vin_detect          VIN 检测识别（沿用 gosmart 兼容路径）
POST   /cv/ocr/v1/vin_compare         VIN 双图字形比对
POST   /cv/ocr/v1/vin_character_compare    字符级比对
POST   /cv/ocr/v1/vin_character_detect     字符抠图
POST   /cv/ocr/v1/vin_more_compare    多图比对
POST   /cv/ocr/v1/vin_rubbing         拓印 VIN 识别

POST   /v1/llm/chat                   LLM 对话（流式 / 非流式，模板驱动）
GET    /v1/llm/templates              prompt 模板列表

# admin 仅在内部域名 / mTLS 暴露，不通过 App 网关
GET    /admin/v1/users?status=pending          待审核用户
POST   /admin/v1/users/:id/approve             通过审核
POST   /admin/v1/models/:name/activate         切换模型 active 版本
POST   /admin/v1/catalog/vehicles              车型档案入库
POST   /admin/v1/catalog/vehicles/:id/publish  审核通过发布
POST   /admin/v1/vin-refs/:id/publish          字形样本审核发布
POST   /admin/v1/shapes/:id/publish            3D 外廓审核发布
POST   /admin/v1/llm/templates                 LLM prompt 模板管理
```

详细字段、状态码、错误码到 `01-api-contract.md`。

## 11. 反推到的非功能要求

- **离线兜底**：App 在弱网时本地缓存查验数据，恢复后批量上传（detail TBD：M1.5 提案）
- **大文件断点续传**：3D 扫描分片，每片 1-4 MB；用 ETag 续传
- **顺序保证**：消息走单调递增 server_seq；客户端按 seq 去重
- **数据保密**：图片 / 扫描数据不能出公网；MinIO 走内网域名 + 临时签名 URL（5 分钟过期）
- **审计**：所有数据修改写 `audit_log` 表（who / when / what / before / after）
