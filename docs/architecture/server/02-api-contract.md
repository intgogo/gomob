# 02 — API 契约（v1）

> 这是 App 与 Gateway 之间的**单一真理源**。任何字段 / 错误码 / 状态机改动必须先改本文档，
> 再改服务端 + App，并附上端到端测试。

## 1. 通用约定

### 1.1 Base URL

- 生产：`https://<gateway-domain>:8808/v1/...`
- 开发（模拟器）：`http://10.0.2.2:8808/v1/...`（10.0.2.2 是 emulator 看到的宿主机）
- 开发（真机 ADB Wi-Fi）：`http://<电脑局域网 IP>:8808/v1/...`

### 1.2 Headers

| Header | 用途 |
|--------|------|
| `Authorization: Bearer <access_token>` | 所有非 auth 接口必带 |
| `X-Gomob-Client` | `android/0.1.0` 等版本标识 |
| `X-Gomob-Trace-Id` | 客户端可选生成；用于服务端 trace 关联 |
| `Content-Type: application/json; charset=utf-8` | 默认 |
| `Accept-Language: zh-CN` | 错误消息本地化（暂只支持 zh-CN） |

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
  "id": "c_1",
  "kind": "p2p",
  "title": "周科",
  "peer": { "id": "12346", "name": "周科" },
  "last_message": {
    "kind": "video_call",
    "preview": "[视频通话 56:02]",
    "created_at": "2024-05-10T17:11:00Z"
  },
  "unread_count": 0
}
```

### 7.2 单会话历史

`GET /v1/conversations/:id/messages?since_seq=&limit=`

```json
{
  "id": "m_999",
  "conversation_id": "c_1",
  "server_seq": 1234,
  "sender_id": "12346",
  "kind": "text",
  "payload": { "text": "好的" },
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

## 8. WebSocket 通道

`WS /v1/ws?token=<access_token>` —— 单连接复用消息推送 + 视频信令。

### 8.1 协议帧

```json
{ "type": "msg.new", "payload": <Message 结构> }
{ "type": "msg.read", "payload": { "conversation_id": "c_1", "up_to_seq": 1234 } }
{ "type": "call.invite", "payload": { "call_id": "...", "from": "...", "sdp": "..." } }
{ "type": "call.answer", "payload": { "call_id": "...", "sdp": "..." } }
{ "type": "call.ice", "payload": { "call_id": "...", "candidate": "..." } }
{ "type": "call.bye", "payload": { "call_id": "...", "reason": "..." } }
{ "type": "ai.preliminary_done", "payload": { "inspection_id": "...", "verdict": "warning" } }
{ "type": "review.assigned", "payload": { "review_id": "...", "expire_at": "..." } }
{ "type": "ping" } / { "type": "pong" }   // 30s 心跳
```

### 8.2 重连策略

- 客户端：指数退避（1s → 30s 上限），最多 30 次后弹"网络异常"
- 服务端：每 30s 推 `ping`，60s 没收到客户端任何帧就 close
- 重连后客户端发 `{"type":"sync","payload":{"last_seqs":{"c_1":1234,...}}}` 拉离线增量

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

## 11. 安全

- TLS 强制（生产）；开发可走 HTTP（10.0.2.2 安全域）
- 密码：bcrypt cost 12
- JWT：HS256 + 服务端密钥；access 2h，refresh 7d
- 资产签名 URL 5 分钟过期；不直接暴露对象存储
- CSRF：不需要（移动 App，不用浏览器 cookie）

## 12. 版本演进

- 路径前缀 `/v1` 锁定；不兼容改动出 `/v2`
- 字段新增不破坏老客户端（下发更多字段允许，旧客户端忽略）
- 删除字段：先 `deprecated` 标记 + 公告 1 个月，再下线

---

后续 03 / 04 / 05 / 06 文档：
- `03-data-model.md` — DB schema 细化（含索引 / 分区 / 归档策略）
- `04-state-machines.md` — 上述状态机的迁移合法性矩阵
- `05-error-codes.md` — 错误码完整目录（含示例 + 客户端处理建议）
- `06-rate-limit.md` — 限流细节
