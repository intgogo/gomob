# 02 — API 契约（v1）

> 这是 App 与 Gateway 之间的**单一真理源**。任何字段 / 错误码 / 状态机改动必须先改本文档，
> 再改服务端 + App，并附上端到端测试。

## 1. 通用约定

### 1.1 Base URL

- 生产：`https://<gateway-domain>:8808/v1/...`
- 开发（模拟器）：App 保存 `http://127.0.0.1:8808/v1/...`，`./dev.sh install/run` 自动执行 `adb reverse tcp:8808 tcp:18808` 转到宿主机开发网关；宿主机 dev 端口仍在 18000 段，避免与其它 8080/8808 项目冲突。
- 开发（真机 ADB Wi-Fi）：`http://<电脑局域网 IP>:18808/v1/...`

登录页优先通过同网段服务发现拿 Base URL：App 向 UDP `255.255.255.255:18809`
和本机网卡 broadcast 地址发送 `gomob.discovery.v1`，gateway 回复：

```json
{
  "type": "gomob.gateway.v1",
  "service": "gomob-gateway",
  "name": "gomob-gateway",
  "http_port": 18808,
  "server_ts": 1778200000000
}
```

App 使用 UDP 响应来源 IP + `http_port` 生成 `http://<source-ip>:<http_port>/v1/...`；
多台 gateway 同时响应时登录页列出候选，用户点选后写入同一个端点存储。

### 1.2 Headers

| Header | 用途 |
|--------|------|
| `Authorization: Bearer <access_token>` | 所有非 auth 接口必带 |
| `X-Gomob-Client` | `android/0.1.0` 等版本标识 |
| `X-Gomob-Trace-Id` | 客户端可选生成；用于服务端 trace 关联 |
| `Content-Type: application/json; charset=utf-8` | 默认 |
| `Accept-Language: zh-CN` | 错误消息本地化（暂只支持 zh-CN） |
| `X-Gomob-AppId` | **仅 cv-engine 接口** `/cv/ocr/v1/*`：调用方应用 ID |
| `X-Gomob-Sign` | **仅 cv-engine 接口**：请求体签名（HMAC-SHA256(secret, body+ts+nonce)）|
| `X-Gomob-Ts` | **仅 cv-engine 接口**：UNIX 毫秒时间戳；服务端拒收偏差 > 5 分钟 |
| `X-Gomob-Nonce` | **仅 cv-engine 接口**：本次请求随机串；服务端按 `appid+nonce` 在 5 分钟窗口内防重放 |

### 1.3 响应统一信封

**成功**：

```json
{
  "code": 0,
  "data": { ... }
}
```

**失败**：

```json
{
  "code": 40101,
  "message": "用户名或密码错误",
  "trace_id": "abc-123"
}
```

- HTTP status 与 `code` 都设：HTTP status 用于客户端通用处理（401 重新登录），
  `code` 用于精确错误识别
- `message` 是面向用户的中文文案（i18n）；客户端可直接展示
- `trace_id` 出现在所有错误响应里，便于排障

### 1.4 时间格式

ISO 8601 UTC，毫秒精度：`2026-05-04T01:23:45.678Z`。

### 1.5 分页

- 查询参数：`?cursor=<opaque>&limit=20`（默认 20，最大 100）
- 响应：

```json
{
  "code": 0,
  "data": {
    "items": [...],
    "next_cursor": "eyJpZCI6MTIzfQ==",
    "has_more": true
  }
}
```

Cursor 是不透明字符串（base64 编码内部状态），客户端不解析。

### 1.6 资源标识

所有主键 `id` 用 `int64` → JSON 中**字符串**形式（避免 JS 精度丢失，Kotlin Long 也可序列化）。
例：`"id": "12345"`。

## 2. 错误码

| 区段 | 含义 |
|------|------|
| `0` | OK |
| `1xxxx` | 客户端通用错误（参数、限流） |
| `4xxxx` | 业务错误（auth / 鉴权 / 状态） |
| `5xxxx` | 服务端错误（DB / 上游） |

| Code | HTTP | 含义 |
|------|------|------|
| `0` | 200 | 成功 |
| `10001` | 400 | 参数缺失或格式错误 |
| `10002` | 400 | 字段值越界 |
| `10003` | 429 | 限流（请稍后重试） |
| `40101` | 401 | 用户名或密码错误 |
| `40102` | 401 | token 缺失 / 已过期 |
| `40103` | 403 | 权限不足 |
| `40104` | 403 | 账号未激活（待审核 / 已禁用） |
| `40201` | 409 | 用户名已存在 |
| `40202` | 409 | 工号已存在 |
| `40301` | 404 | 资源不存在 |
| `40401` | 409 | 状态机不允许（如已关闭的查验不能再改） |
| `40501` | 401 | cv-engine 验签失败（sign / ts / nonce 三选一异常） |
| `40502` | 409 | cv-engine 重放检测（nonce 已使用） |
| `40601` | 422 | LLM 模板不存在 / 已下线 |
| `40602` | 429 | LLM 配额超限（按模板 / 按用户） |
| `40603` | 502 | LLM 上游供应商不可用 |
| `40701` | 404 | 车型档案不存在或未发布 |
| `40702` | 404 | 字形参考样本不存在或未发布 |
| `40703` | 404 | 3D 外廓资产不存在或未发布 |
| `50001` | 500 | 服务端内部错误（看 trace_id） |
| `50002` | 502 | 上游不可用（DB / 对象存储） |
| `50003` | 504 | 上游超时 |

## 3. Auth 接口

### 3.1 注册

`POST /v1/auth/register`

**Request**：

```json
{
  "username": "shenhm",
  "password": "Plain text，服务端自己 bcrypt",
  "real_name": "沈海明",
  "employee_id": "ZAA0120230001",
  "station_name_hint": "杭州市西湖区车管所检测站",
  "note": "说明信息可选"
}
```

**Response**（注册成功，需后台审核）：

```json
{
  "code": 0,
  "data": {
    "user_id": "12345",
    "status": "pending",
    "message": "提交成功，请等待后台审核通过"
  }
}
```

**错误**：`10001` 缺字段 / 格式错；`40201` 用户名已存在；`40202` 工号已存在。

### 3.2 登录

`POST /v1/auth/login`

**Request**：

```json
{
  "username": "shenhm",
  "password": "明文",
  "captcha_token": "可选，前端图片验证码 challenge token",
  "captcha_answer": "1234"
}
```

**Response**：

```json
{
  "code": 0,
  "data": {
    "access_token": "eyJhbGc...",
    "refresh_token": "eyJhbGc...",
    "expires_in": 7200,
    "user": {
      "id": "12345",
      "username": "shenhm",
      "real_name": "沈海明",
      "employee_id": "ZAA0120230001",
      "role": "inspector",
      "station": {
        "id": "1",
        "name": "杭州市西湖区车管所检测站"
      }
    }
  }
}
```

**错误**：`40101` 密码错；`40104` 账号未激活。

### 3.3 刷新 token

`POST /v1/auth/refresh`

**Request**：`{"refresh_token": "..."}`

**Response**：同 login 的 `access_token / refresh_token / expires_in`（不重发 user）。

### 3.4 改密码

`POST /v1/auth/password`（需 access_token）

**Request**：

```json
{ "old_password": "...", "new_password": "..." }
```

**Response**：`{"code":0, "data":null}`

错误：`10002` 新密码强度不够；`40101` 旧密码错。

### 3.5 我的信息

`GET /v1/me`

**Response**：与 login 响应中的 `user` 同结构（拿来填首页 / 我的 tab）。

`PATCH /v1/me`：改 `real_name`（其它字段只读，需走管理员）。

## 4. Inspection（查验）接口

### 4.1 列表

`GET /v1/inspections?status=&filter=&cursor=&limit=`

**Query**：

| 参数 | 取值 | 说明 |
|------|------|------|
| `status` | `created/scanning/preliminary/pending_review/closed` | 单值 |
| `filter` | `all/passed/model_anomaly/obd_anomaly/shape_anomaly/pending_review` | 对应首页 chip |
| `since` | ISO 时间 | 起始 |

**Response item**：

```json
{
  "id": "9001",
  "vin": "LSVHM133022221761",
  "vehicle": {
    "brand": "大众系列",
    "type": "小型汽车",
    "plate_no": "沪A12345"
  },
  "preliminary_verdict": "warning",
  "preliminary_reasons": ["OBD 检验", "外廓尺寸"],
  "inspector_id": "12345",
  "created_at": "2024-05-10T03:45:00.000Z"
}
```

### 4.2 详情

`GET /v1/inspections/:id` — 含完整 vehicle 字段、资产清单、复核结论、审计时间线。

### 4.3 创建查验

`POST /v1/inspections`

```json
{
  "vin": "LSVHM133022221761",
  "plate_no": "沪A12345",
  "brand": "大众系列",
  "type": "小型汽车",
  "model_code": "...",
  "year_code": "F",
  "factory_date": "2021-07",
  "color": "白"
}
```

返回创建的 inspection（`status="created"`）。

### 4.4 上传查验资产

详见 §5 Asset 接口；通过 `POST /v1/inspections/:id/assets` 关联。

### 4.5 更新预审结果

`PATCH /v1/inspections/:id/result`

```json
{
  "preliminary_verdict": "warning",
  "preliminary_reasons": ["OBD 检验"]
}
```

通常由 worker 内部调用；查验员仅在"复审"时手动调（权限受 RBAC 控制）。

### 4.6 关闭查验

`POST /v1/inspections/:id/close` — 状态机进入 `closed`，资产不再可改。

## 5. Asset 接口（分片上传）

### 5.1 初始化

`POST /v1/assets/upload/init`

```json
{
  "inspection_id": "9001",
  "kind": "scan3d",
  "size_bytes": 12345678,
  "sha256": "原文件全文 SHA-256（可选断点续传校验）",
  "mime": "application/octet-stream",
  "metadata": { "scan_session_id": "..." }
}
```

**Response**：

```json
{
  "code": 0,
  "data": {
    "upload_id": "u_abc",
    "chunk_size": 4194304,
    "uploaded_chunks": []
  }
}
```

### 5.2 上传分片

`PUT /v1/assets/upload/:upload_id/chunk/:n`

Body：原始字节流（非 multipart，简化）。Header：`Content-Length: <size>`。

### 5.3 完成

`POST /v1/assets/upload/:upload_id/complete`

```json
{ "total_chunks": 4 }
```

**Response**：

```json
{
  "code": 0,
  "data": {
    "asset_id": "a_999",
    "object_key": "assets/scan3d/9001/u_abc.bin",
    "download_url": "https://.../signed?exp=..."
  }
}
```

### 5.4 下载（签名 URL）

`GET /v1/assets/:asset_id/url` — 返回 5 分钟有效的临时签名 URL。

## 6. Review（抽查复核）接口

### 6.1 待复核列表

`GET /v1/reviews?bucket=pending&cursor=...`

`bucket`：`pending / done / expired`。

### 6.2 复核详情

`GET /v1/reviews/:id` — 含原 inspection、车架号图、辅助图、合规性异常清单。

### 6.3 提交复核结果

`POST /v1/reviews/:id/decision`

```json
{ "decision": "correct", "reason": "可选文字" }
```

`decision`：`correct / incorrect / skipped`。

## 7. Message 接口

### 7.1 会话列表

`GET /v1/conversations?cursor=&limit=`

```json
{
  "items": [
    {
      "id": "1",
      "kind": "p2p",
      "title": "周科",
      "peer": { "id": "12346", "name": "周科", "employee_id": "ZAA0120230002" },
      "last_message": {
        "id": "999",
        "sender_id": "12346",
        "server_seq": 42,
        "kind": "video_call",
        "preview": "[视频通话 56:02]",
        "client_msg_id": "可选，发送方本地 UUID",
        "created_at": "2026-05-08T17:11:00Z"
      },
      "last_read_seq": 40,
      "unread_count": 2,
      "updated_at": "2026-05-08T17:11:00Z"
    }
  ],
  "next_cursor": "0",
  "has_more": false
}
```

### 7.2 单会话历史

`GET /v1/conversations/:id/messages?since_seq=&limit=&latest=`

默认按 `since_seq` 做离线增量补齐；`latest=true` 时忽略 `since_seq`，返回该会话最新
`limit` 条消息，并仍按 `server_seq` 升序排列，供 App 首屏预热使用。

若当前用户删除过该会话，REST 历史和 WebSocket `msg.fetch` 都只返回删除边界之后的消息；
客户端传入更早的 `since_seq` 不能越过服务端权威边界。

```json
{
  "id": "m_999",
  "conversation_id": "c_1",
  "server_seq": 1234,
  "sender_id": "12346",
  "kind": "text",
  "payload": { "text": "好的" },
  "client_msg_id": "可选，发送方本地 UUID",
  "created_at": "..."
}
```

### 7.3 发送消息

`POST /v1/conversations/:id/messages`

```json
{
  "client_msg_id": "随机 UUID，服务端去重",
  "kind": "text",
  "payload": { "text": "..." }
}
```

返回服务端 `server_seq` 等。

服务端按 `(sender_id, client_msg_id)` 幂等；同一客户端重试不会重复分配 `server_seq`，
也不会重复推送给收件人。
会话未读数只统计当前用户 `last_read_seq` 之后的他人/系统消息；自己发出的消息不制造未读。

### 7.4 标记已读

`POST /v1/conversations/:id/read`

```json
{ "last_read_seq": 1234 }
```

返回：

```json
{
  "conversation_id": "1",
  "last_read_seq": 1234,
  "unread_count": 0
}
```

### 7.5 删除当前用户的会话

`DELETE /v1/conversations/:id`

该操作只删除当前用户视角下的会话及既有历史，不删除共享消息、不移除成员关系，也不影响
其他成员。服务端在会话行锁内读取当时的 `next_seq`，把此前消息设为该用户不可见；因此与
并发消息写入之间具备明确顺序：先完成的消息被隐藏，后完成的消息拿到更大序号并恢复会话。

返回：

```json
{
  "conversation_id": "1",
  "deleted_before_seq": 43
}
```

`deleted_before_seq` 是排他删除边界：当前用户本地与服务端都应删除 / 隐藏
`server_seq < deleted_before_seq` 的消息。后续首条消息的 `server_seq >= deleted_before_seq`，
会话会自动重新出现在列表中，历史只从该边界开始返回。

### 7.6 退出群聊

`POST /v1/conversations/:id/leave`

仅 `kind=group` 会话支持。服务端移除当前用户在该会话的成员关系和成员状态；历史消息不删除，仍对其它成员可见。

返回：

```json
{
  "conversation_id": "1",
  "left": true
}
```

## 8. WebSocket 通道（M-S4 已实施）

`WS /v1/ws?token=<access_token>` —— 单连接复用消息推送 + 视频信令。

> M-S4 的 `call.invite / call.answer / call.ice` 是 P2P 信令验证骨架。M5 起视频通话和第一视角直播升级为
> [09 实时消息与第一视角协作](../09-realtime-message-live.md) 的媒体房间模型：WebSocket 只承载邀请、
> 状态和批注，SDP / ICE 由自托管 LiveKit 媒体面处理。

**鉴权**：浏览器 / RN WebSocket 不能设自定义 header，约定 `?token=<access JWT>` query；
gateway 路由声明 `Public:true` 透传 ws upgrade，token 校验由 signaling 自己做。

### 8.1 帧外壳

所有帧统一 `Envelope`：

```json
{ "type": "<事件类型>", "payload": { ... }, "frame_seq": 12 }
```

`frame_seq` 是连接级单调递增（不同于消息 server_seq），便于客户端调试。
错误帧额外含 `code` / `message`。

### 8.2 客户端 → 服务端

| type | payload | 说明 |
|------|---------|------|
| `msg.send` | `{to_user_id, kind, content, client_msg_id?}` | 单聊；服务端自动建 / 拿 p2p 会话 |
| `msg.fetch` | `{conversation_id, since_seq, limit}` | 离线补齐 |
| `call.invite` | `{to_user_id, sdp}` | 视频呼叫；离线时入 `pending_calls` |
| `call.answer` | `{call_id, to_user_id, sdp}` | 应答 |
| `call.ice` | `{call_id, to_user_id, candidate}` | ICE 透传 |
| `call.bye` | `{call_id, to_user_id, reason}` | 挂断 |

### 8.3 服务端 → 客户端

| type | payload | 说明 |
|------|---------|------|
| `hello` | `{user_id, role, server_ts}` | 握手成功 |
| `msg.delivered` | `{client_msg_id?, conversation_id, server_seq, message_id, created_at}` | 发送方回执 |
| `msg.recv` | `{conversation_id, server_seq, sender_id, kind, content, created_at}` | 推给收件人 |
| `msg.fetch_result` | `{conversation_id, items[], next_since_seq}` | 离线补齐结果（升序） |
| `call.invite` | `{call_id, from_user_id, sdp, pending?, created_at}` | 收到邀请；`pending=true` 表示来自离线缓存 |
| `call.invite_ack` | `{call_id, online, ttl_sec?}` | 主叫回执；`online=false` 表示已入 `pending_calls` |
| `call.answer` | `{call_id, from_user_id, sdp}` | 收到应答 |
| `call.ice` | `{call_id, from_user_id, candidate}` | 收到 ICE |
| `call.bye` | `{call_id, from_user_id, reason}` | 收到挂断 |
| `error` | `{in_reply_to}` + `code` + `message` | 错误帧 |

### 8.4 server_seq 单调保证

- 每个 `conversations` 行有 `next_seq BIGINT NOT NULL DEFAULT 1`
- 写消息时 `UPDATE conversations SET next_seq=next_seq+1 WHERE id=$1 RETURNING next_seq-1` 行锁分配 → 严格单调，不依赖 SELECT MAX(seq)+1 的竞争窗口
- `UNIQUE(conversation_id, server_seq)` 约束兜底重复检测
- harness `ws_message_order` S6 验证：5 goroutine × 20 msg = 100 条并发，seq 严格 [52..151] 无重复无空洞

### 8.5 离线 invite 兜底

- `call.invite` 时 `Hub.Push` 返回 0 → 写 `pending_calls`（默认 60s TTL）
- callee 上线 → `DeliverPending` 拉未过期 invite 一次性下发并 `MarkDelivered`
- 后台 `SweepLoop`（默认 30s）把过期 pending → `expired`

### 8.6 心跳 / 重连

- 服务端：`pingPeriod=25s` 主动推 ws ping；`pongWait=60s` 内未收到 pong 关连接
- 客户端建议：指数退避重连（1s→30s 上限）；重连后发 `msg.fetch` 按 `since_seq=last_known` 补齐

## 9. 状态机

### 9.1 用户

```
pending  ─审核通过─▶  active  ─管理员禁用─▶  disabled
                                ▲
                                │ 管理员启用
                            disabled
```

### 9.2 查验

```
created ──上传资产──▶ scanning ──预审完成──▶ preliminary
   │                                            │
   │                                            ├─无需复核──▶ closed
   │                                            └─抽中复核──▶ pending_review ──复核完成──▶ closed
   └─取消──▶ closed
```

### 9.3 复核

```
assigned ──复核完成──▶ done
   └─到期未做──▶ expired
```

## 10. 限流 / 配额

| 资源 | 默认限额 | header 提示 |
|------|----------|-------------|
| 登录 | 5 次/分钟 / IP | `X-RateLimit-Remaining` |
| 注册 | 1 次/分钟 / IP | 同上 |
| 上传分片 | 20 MB/s / user | 自然带宽限制 |
| WS 帧 | 100 帧/s / connection | 超过断连 |
| `/v1/catalog/*` GET | 600 次/分钟 / user | 触限 `40703` 统一 |
| `/cv/ocr/v1/vin_*` | 60 次/分钟 / user + 10 次/秒 / appid | 触限 `10003` |
| `/v1/llm/chat` 调用次数 | 模板级 day budget（admin 配置）+ 30 次/分钟 / user | 触限 `40602` |
| `/v1/llm/chat` token 配额 | 模板级 day token budget；超时返 `40602` | 同上 |

## 11. 安全

- TLS 强制（生产）；开发可走 HTTP（10.0.2.2 安全域）
- 密码：bcrypt cost 12
- JWT：HS256 + 服务端密钥；access 2h，refresh 7d
- 资产签名 URL 5 分钟过期；不直接暴露对象存储
- CSRF：不需要（移动 App，不用浏览器 cookie）
- **cv-engine 双轨鉴权**：JWT（用户身份）+ HMAC-SHA256 验签（防重放 / 防伪造）；secret 由 admin 颁发，5 分钟时间窗 + nonce 去重，详见 §14.1
- **LLM 凭证不下发到客户端**：DeepSeek 等供应商 API key 仅存 llm-gateway configs（通过 KMS / sealed secret 注入），App 端无任何上游凭证
- **审计完整性**：cv-engine 调用与 llm-gateway 调用都落 `audit_log` + 各自专表（`cv_call_logs` / `llm_call_logs`），便于成本归因与异常溯源

## 12. 版本演进

- 路径前缀 `/v1` 锁定；不兼容改动出 `/v2`
- 字段新增不破坏老客户端（下发更多字段允许，旧客户端忽略）
- 删除字段：先 `deprecated` 标记 + 公告 1 个月，再下线

## 13. Catalog（车型档案 / 字形参考 / 3D 外廓 — 参考库三件套）

> 通路：HTTP 入口由 **api 服务**承担（`/v1/catalog/*`），api 内部 gRPC 转发到 vehicle-catalog / vinref / shaperef。
> App 端鉴权统一 JWT，无需验签。

### 13.1 车型档案查询

`GET /v1/catalog/vehicles?make=&series=&year=&keyword=&cursor=&limit=`

**Response**：

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "vm_10001",
        "make": "比亚迪",
        "series": "汉",
        "year": 2024,
        "engine_type": "EV",
        "outline_features": {
          "length_mm": 4995, "width_mm": 1910, "height_mm": 1495,
          "wheelbase_mm": 2920
        },
        "compliance_check_list": ["合规项-001", "合规项-007"],
        "updated_at": "2026-04-21T08:00:00.000Z"
      }
    ],
    "next_cursor": "...",
    "has_more": false
  }
}
```

### 13.2 车型档案详情

`GET /v1/catalog/vehicles/:id`

**Response**：在 13.1 字段基础上额外返回：

```json
{
  "vin_ref_count": 3,
  "shape_ref_id": "vs_50001",
  "manufacturer_doc_url": "https://.../signed?exp=..."
}
```

### 13.3 字形参考样本（M-S8 已实施）

> 设计第一性：字段对齐 gosmart `apps/api/ivv/item.go` `VinMore`（character / arr_mode /
> font_id / font_family_id / alpha_image_data / origin_image_data），按"车型 × 批次 × 字符"三层索引到字符级。
> M-S10 cv-engine 把 `doCompareVin` 改成"按 vehicle_model_id+character 拉对照样本与本次扫描字符比对"是单点改动。

#### 13.3.1 数据模型

```
vehicle_models ─< vin_glyph_batches ─< vin_glyph_samples
                  ├ name (UNIQUE per vehicle_model)
                  ├ status: draft → published → archived
                  ├ partial unique: 同 vehicle_model 至多 1 published
                  └ sample_count（触发器自动维护）
```

每个样本字段（CHECK：character ∈ VIN 33 字符 0-9 + A-Z 去 I/O/Q；arr_mode ∈ {0,1,2,3}；qc_score ∈ [0,1]）：

| 字段 | 类型 | 说明（对应 gosmart） |
|------|------|---------------------|
| `character` | CHAR(1) | VIN 字符（VinMore.Character） |
| `arr_mode` | int16 | 0 unknown / 1 line / 2 dline / 3 arc（VinMore.ArrMode） |
| `font_id` | text | 模板 / 厂家字体 ID（VinMore.FontId，默认 `*`） |
| `font_family_id` | text | 字体族 ID（多字体批次分组） |
| `position_hint` | int16 | 1..17 VIN 位置（NULL = 通用） |
| `alpha_object_key/sha256/size_bytes` | text/text/int64 | 掩膜 webp（VinMore.AlphaImageData，落 MinIO 不入库） |
| `origin_object_key/sha256/size_bytes` | text/text/int64 | 原始彩色图 webp（VinMore.OriginImageData，可选） |
| `feature_vector_uri` | text | M-S10 cv-engine 预提的特征向量（可选） |
| `qc_score` | float32 | 入库质检分 0-1 |

#### 13.3.2 App 读路径（仅 published）

`GET /v1/catalog/vehicles/{vmid}/vin-refs/active` —— active 批次摘要 + 按字符样本计数

```json
{
  "code": 0,
  "data": {
    "batch": {
      "id": "vrb_42", "vehicle_model_id": "vm_10001",
      "name": "factory_2024Q1", "sample_count": 11,
      "status": "published", "published_at": "2026-05-04T..."
    },
    "counts_by_char": {"A": 2, "B": 2, "1": 2, "9": 2, "Z": 3}
  }
}
```

`GET /v1/catalog/vehicles/{vmid}/vin-refs/active/samples?character=A&position_hint=&limit=200` —— 拿对照样本集

```json
{
  "code": 0,
  "data": {
    "batch_id": "vrb_42", "batch_name": "factory_2024Q1", "status": "published",
    "items": [
      {
        "id": "vrs_103", "batch_id": "vrb_42",
        "character": "A", "arr_mode": 1, "font_id": "*",
        "font_family_id": null, "position_hint": null,
        "alpha_object_key": "vin-refs/vm_10001/A/abcdef.webp",
        "alpha_sha256": "...", "alpha_size_bytes": 4096,
        "origin_object_key": null, "origin_sha256": null, "origin_size_bytes": null,
        "feature_vector_uri": null,
        "qc_score": 0.95,
        "created_at": "2026-05-04T..."
      }
    ]
  }
}
```

`GET /v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/samples?character=...` —— 指定批次（仅 published 对 App 可见，admin 全状态）

> 图片字节流走 asset 签名 URL（`alpha_object_key` 用 asset `/v1/assets/presign?key=...` 拿 5 分钟签名）。

#### 13.3.3 admin 写路径

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches` | 创建 draft 批次 |
| `GET` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches?status=&limit=&cursor=` | 列批次（全状态） |
| `GET` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}` | 批次详情 |
| `PATCH` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}` | 改 draft 元数据；非 draft → 40401 |
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/publish` | 发布；旧 active 自动 archive（事务 + 行锁） |
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/archive` | 归档 |
| `DELETE` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}` | 仅 draft 可删（CASCADE 删样本） |
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/samples` | 写样本；非 draft 批次返 40401；非法字符 / arr_mode 越界 → 10002 |
| `DELETE` | `/admin/v1/catalog/vehicles/{vmid}/vin-refs/samples/{sid}` | 删样本（仅 draft 批次） |

错误码：`40201` 重名；`40401` 状态机不允许；`40701` vehicle_model 不存在 / 无 active 批次；`10002` CHECK 约束（字符不在 VIN 33 字符集 / arr_mode 越界 / qc_score 越界）。

### 13.4 车型 3D 外廓（M-S9 已实施）

> 设计第一性：mesh 是单文件资产 ─ 不引入 vin-ref 的 batch×sample 双层结构。
> 一条 `vehicle_shapes` = 一个完整版本，状态机 `draft→published→archived` 直接挂记录上；
> partial unique 保证同 vehicle_model 至多 1 published。
> mesh 本体不入 PG（GB 级），只存 `mesh_object_key`/`mesh_sha256`/`mesh_size_bytes`/`mesh_format` + 几何元数据；
> 字节流走 asset MinIO，**5 分钟**签名 URL，客户端可 HTTP `Range` 续传。

#### 13.4.1 数据模型

```
vehicle_models ─< vehicle_shapes
                  ├ version_name UNIQUE per vehicle_model
                  ├ status: draft → published → archived
                  ├ partial unique: 同 vehicle_model 至多 1 published
                  ├ source: factory_cad / scan_high_res / manual_modeled / unknown （CHECK）
                  └ mesh_format: glb / ply / stl / obj / gltf （CHECK）
```

字段（CHECK：`mesh_format`、`source` 白名单；`coverage` / `qc_score` ∈ [0,1]；`mesh_size_bytes > 0`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `version_name` | text | 版本名（"v1.0_2024Q1" / "factory_cad_2024_03"） |
| `description` | text | 版本说明 |
| `source` | text | factory_cad（厂家 CAD）/ scan_high_res（高精度扫描）/ manual_modeled（手工建模）/ unknown |
| `captured_at` / `captured_by` | timestamptz / text | 厂家提供 / 扫描重建日期 + 上报人 |
| `mesh_object_key` | text | asset MinIO key（admin 先用 asset 服务上传，再用 key 注册元数据） |
| `mesh_sha256` | text | 内容寻址；下载校验用 |
| `mesh_size_bytes` | int64 | 字节大小（CHECK > 0） |
| `mesh_format` | text | glb / ply / stl / obj / gltf（CHECK） |
| `triangle_count` | int64 | 网格面数 |
| `point_count` | int64 | 点云点数（点云型 PLY 等） |
| `bbox_min_x..z` / `bbox_max_x..z` | float32 × 6 | 局部坐标系 bbox |
| `coverage` | float32 | 0-1 扫描覆盖度（点云 / 重建专用） |
| `qc_score` | float32 | 0-1 质检分 |
| `qc_notes` | text | 质检备注 |
| `note` | text | 自由备注 |
| `published_at` / `archived_at` | timestamptz | 状态机时间戳 |

#### 13.4.2 App 读路径（仅 published）

`GET /v1/catalog/vehicles/{vmid}/shape` —— active 版本元数据 + 签名 mesh URL（5 分钟）

```json
{
  "code": 0,
  "data": {
    "id": "vs_50001",
    "vehicle_model_id": "vm_10001",
    "version_name": "v1.0_2024Q1",
    "source": "factory_cad",
    "mesh_object_key": "orphan/scan3d/u_abc.bin",
    "mesh_sha256": "...",
    "mesh_size_bytes": 1342177280,
    "mesh_format": "glb",
    "mesh_download_url": "https://minio/.../signed?X-Amz-...&X-Amz-Expires=300",
    "mesh_url_expire_at": "2026-05-04T22:07:24.07096506Z",
    "triangle_count": 8500000,
    "bbox": {"min_x": -2.5, "min_y": -1.0, "min_z": -2.5, "max_x": 2.5, "max_y": 1.5, "max_z": 2.5},
    "coverage": 0.97,
    "qc_score": 0.92,
    "status": "published",
    "published_at": "2026-04-10T03:00:00.000Z",
    "created_at": "...",
    "updated_at": "..."
  }
}
```

`GET /v1/catalog/vehicles/{vmid}/shape/url` —— 只刷新签名 URL（带宽优化 / 链接过期续签）

`GET /v1/catalog/vehicles/{vmid}/shapes/{sid}` —— 指定历史版本（仅 published 对 App；admin 全状态可见）

> 大文件下载：客户端通过 HTTP `Range` 请求分段拉取（MinIO 原生支持）；签名 URL 5 分钟过期，客户端按需调 `/shape/url` 续签。

#### 13.4.3 admin 写路径

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/shapes` | 创建 draft（admin 先用 asset 上传 mesh 拿 object_key，再注册元数据） |
| `GET` | `/admin/v1/catalog/vehicles/{vmid}/shapes?status=&limit=&cursor=` | 列版本（全状态） |
| `GET` | `/admin/v1/catalog/vehicles/{vmid}/shapes/{sid}` | 版本详情 |
| `PATCH` | `/admin/v1/catalog/vehicles/{vmid}/shapes/{sid}` | 改 draft 元数据；非 draft → 40401 |
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/shapes/{sid}/publish` | 发布；旧 active 自动 archive（事务 + 行锁） |
| `POST` | `/admin/v1/catalog/vehicles/{vmid}/shapes/{sid}/archive` | 归档 |
| `DELETE` | `/admin/v1/catalog/vehicles/{vmid}/shapes/{sid}` | 仅 draft 可删 |

错误码：`40201` 重名 / `40401` 状态机不允许 / `40701` vehicle_model / shape 不存在 / `10002` CHECK 约束（mesh_format 不在白名单 / coverage 越界等）。

### 13.5 写入路径（仅 admin）

录入 / 修订 / 发布全部走 admin BFF，详见 `00-server-overview.md` §10 admin 路径，App 不可见。

## 14. cv-engine（VIN 检测 / 字形比对）

> 通路：gateway 直达。**双轨鉴权**：`Authorization: Bearer ...` (JWT) + `X-Gomob-AppId/Sign/Ts/Nonce` (验签)。
> 路径与 gosmart 时代保持一致以兼容已有客户端。详细字段见 `gosmart/docs/vin-detect-compare-capability.md`。

### 14.1 验签算法

```
sign = base64( HMAC-SHA256(
    secret,
    method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + sha256_hex(body)
))
```

- `secret` 由 admin 后台为每个 `appid` 颁发，下发到 App 时通过 KMS 加密
- 服务端窗口：`|server_ts - X-Gomob-Ts| < 5 分钟`，超出返 `40501`
- 重放：`(appid, nonce)` 在 5 分钟内不可重复，触发返 `40502`

### 14.2 VIN 检测识别

`POST /cv/ocr/v1/vin_detect`

```json
{
  "image": "<base64 或 url 或 multipart 文件>",
  "vehicle_model_id": "vm_10001",
  "expected_vin": "LGBH12E0XJB123456",
  "options": { "return_crop": true, "return_chars": true }
}
```

**Response**：

```json
{
  "code": 0,
  "data": {
    "version": "vin-2.3.1",
    "vin": [
      {
        "value": "LGBH12E0XJB123456",
        "confidence": 0.987,
        "rect_xyxy": [120, 480, 980, 540],
        "rect_xywh": [120, 480, 860, 60],
        "crop_image": "<base64 webp>",
        "chars": [
          { "ch": "L", "confidence": 0.99, "bbox": [...] }
        ]
      }
    ]
  }
}
```

#### 14.2.1 App 识别代理

`POST /cv/ocr/v1/vin_recognize` 接收服务端权威还原 PNG 的 multipart 字段
`image_binary`。Gomob 服务端代签调用现场外部算法；私钥仅由 cv-engine 通过只读部署密钥
挂载加载，禁止写入源码、镜像或 Android APK。

成功响应：

```json
{
  "code": 0,
  "data": {
    "provider": "gosmart",
    "vin": "ABC",
    "confidence": 0.98,
    "character_scores": [0.99, 0.98, 0.97],
    "character_count": 3,
    "log_id": "...",
    "infer_ms": 327,
    "character_crops": [
      {
        "position": 1,
        "character": "A",
        "image": {
          "mime_type": "image/webp",
          "data_base64": "<裸 base64，不带 data: 前缀>",
          "width": 64,
          "height": 128
        }
      },
      {
        "position": 2,
        "character": "B",
        "image": {
          "mime_type": "image/webp",
          "data_base64": "<裸 base64，不带 data: 前缀>",
          "width": 64,
          "height": 128
        }
      },
      {
        "position": 3,
        "character": "C",
        "image": {
          "mime_type": "image/webp",
          "data_base64": "<裸 base64，不带 data: 前缀>",
          "width": 64,
          "height": 128
        }
      }
    ]
  }
}
```

示例使用 3 位结果便于阅读；正常 VIN 的 `character_scores`、`character_crops` 和
`character_count` 均为 17。非 17 位结果仍可按实际长度返回，由 App 标记“需复核”。

代理不发送外部算法的 `skip_image`。单字符素材只取所选 VIN item 的
`more[].origin_image_data`；`alpha_image_data` 是机器比对掩膜，`result.image[].vin_detect_image`
是整行检测图，两者都不下发 App。返回前必须保证：

- `character_scores`、`character_crops` 数量等于 VIN 字符数，`position` 从 1 连续递增；
- `character_crops[].character` 按顺序拼接后等于 VIN；
- 每张图严格 base64 解码、WebP 全量解码且尺寸为 `64×128`，单图和总大小不越界；
- `more` 缺失或损坏直接返回上游失败，禁止回退整行图、拆分整行图或用文本生成字符图。

### 14.3 VIN 双图比对

`POST /cv/ocr/v1/vin_compare`

```json
{
  "image_a": "<图 A>",
  "image_b": "<图 B>",
  "vehicle_model_id": "vm_10001"
}
```

**Response**：

```json
{
  "code": 0,
  "data": {
    "vin": "LGBH12E0XJB123456",
    "match": true,
    "string_equal": true,
    "char_similarity": [
      { "ch": "L", "score": 0.96 }
      // ...
    ],
    "overall_score": 0.93
  }
}
```

> 当 `vehicle_model_id` 提供时，cv-engine 内部 gRPC 调 vin-ref 拉对照样本，比对结果会带 `manufacturer_match: true|false`。

### 14.4 其它接口

字段细节同 gosmart 文档 §2.3 ~ §2.6：
- `POST /cv/ocr/v1/vin_more_compare`
- `POST /cv/ocr/v1/vin_character_compare`
- `POST /cv/ocr/v1/vin_character_detect`
- `POST /cv/ocr/v1/vin_rubbing`

迁移过程中保持请求 / 响应字段不变，仅鉴权从"仅验签"升级为"JWT + 验签双轨"。

## 15. LLM 大模型网关

> 通路：gateway 直达。鉴权：JWT。流式：HTTP SSE（`Accept: text/event-stream`）。

### 15.1 对话（流式 / 非流式）

`POST /v1/llm/chat`

```json
{
  "template_id": "tmpl_vin_audit_v1",
  "vars": {
    "vin": "LGBH12E0XJB123456",
    "ocr_confidence": 0.93,
    "manufacturer_match": true
  },
  "stream": true,
  "preferred_provider": "deepseek"
}
```

**非流式 Response**：

```json
{
  "code": 0,
  "data": {
    "request_id": "llm_a1b2",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "content": "建议人工复核车驾号字形 ...",
    "token_in": 312,
    "token_out": 198,
    "latency_ms": 1842
  }
}
```

**流式 Response**（SSE）：

```
event: meta
data: {"request_id":"llm_a1b2","provider":"deepseek","model":"deepseek-chat"}

event: delta
data: {"content":"建议"}

event: delta
data: {"content":"人工"}

...

event: done
data: {"token_in":312,"token_out":198,"latency_ms":1842}
```

客户端关闭 SSE 连接时，网关向上游发送取消信号（节省成本）。

### 15.2 模板列表

`GET /v1/llm/templates?cursor=&limit=`

**Response**：

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "tmpl_vin_audit_v1",
        "name": "VIN 字形比对辅助研判",
        "version": 3,
        "preferred_provider": "deepseek",
        "vars_schema": {
          "vin": "string",
          "ocr_confidence": "number",
          "manufacturer_match": "boolean"
        },
        "updated_at": "2026-05-01T10:00:00.000Z"
      }
    ]
  }
}
```

### 15.3 写入路径（仅 admin）

模板上传 / 修订 / 下线由 admin BFF 承担，App 不可见。

---

后续 03 / 04 / 05 / 06 文档：
- `03-data-model.md` — DB schema 细化（含索引 / 分区 / 归档策略）
- `04-state-machines.md` — 上述状态机的迁移合法性矩阵
- `05-error-codes.md` — 错误码完整目录（含示例 + 客户端处理建议）
- `06-rate-limit.md` — 限流细节
