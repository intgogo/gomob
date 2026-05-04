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
        │   │  Auth   API   Asset   Signaling   Worker   │ │
        │   │   │      │      │        │           │     │ │
        │   └───┴──────┴──────┴────────┴───────────┴─────┘ │
        │       │      │      │                    │       │
        │   PostgreSQL  Redis  MinIO/OSS         AI 模型     │
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
| **signaling** | 消息中心 + WebRTC 视频通话信令 + 推送 | Go + WebSocket + Redis pub/sub |
| **worker** | 智能预审 AI（OBD/外观/合规/年份码校验）、缩略图、PDF 生成 | Go + 异步任务队列（Asynq/NATS） |
| **admin (Web)** | 管理后台（注册审核、用户/组织管理） | 不在本仓 |

**为何不"单体"：** 信令服务和 API 业务的并发模型完全不同（信令长连接 + 事件驱动；
API 短连接 + 同步 CRUD）。混在一起会让任何一边的容量规划被另一边污染。
**为何不更碎：** 不必要的微服务化会引入分布式事务复杂度。当前 5 个服务覆盖
5 个独立资源边界，刚好够用。

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

## 7. 信令 / 视频通话

- App 通过 wss 长连接到 signaling 服务
- 主叫发起：`POST /v1/calls/{peer_id}/invite` → signaling 通过 wss 推 invite 到被叫
- 双方 SDP / ICE candidate 交换走 wss 转发
- 媒体走 WebRTC peer-to-peer（同检测站内网走局域网，跨网走 TURN）
- 通话记录元数据（开始/结束/时长）落 `call_logs` 表

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
WS     /v1/ws                         消息 / 信令双向通道
```

详细字段、状态码、错误码到 `01-api-contract.md`。

## 11. 反推到的非功能要求

- **离线兜底**：App 在弱网时本地缓存查验数据，恢复后批量上传（detail TBD：M1.5 提案）
- **大文件断点续传**：3D 扫描分片，每片 1-4 MB；用 ETag 续传
- **顺序保证**：消息走单调递增 server_seq；客户端按 seq 去重
- **数据保密**：图片 / 扫描数据不能出公网；MinIO 走内网域名 + 临时签名 URL（5 分钟过期）
- **审计**：所有数据修改写 `audit_log` 表（who / when / what / before / after）
