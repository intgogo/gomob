# 09 — 实时消息与第一视角协作设计

> M5 主线权威设计。目标是把“消息、视频通话、第一视角直播”做成查验业务的实时协作能力，
> 而不是一个独立聊天应用。
>
> 资料核对日期：2026-05-08。LiveKit / WebRTC 相关外部事实以本文末尾官方资料为准。

## 1. 业务定义

机动车查验现场有三类实时协作：

1. **消息**：查验员、监管员、复核员之间围绕 VIN、工单、预审结果沟通；支持单聊、群、系统通知、图片 / 语音 / 视频片段 / 查验资产引用。
2. **视频通话**：从单聊里发起 1:1 或小范围会诊，通话结束后在会话里留下 `[视频通话 56:02]` 记录。
3. **第一视角直播**：查验员在工位发布实时画面，监管员 / 复核员观看、语音介入、截图存档、标记预警；直播结束后可生成录像供培训或复核。

第一视角直播不是公开直播产品。它的核心价值是监管链路可解释：谁看了、谁介入、谁标记、哪段视频被存档，都必须能追溯。

## 2. 第一性结论

### 2.1 控制面自研，媒体面交给 SFU

消息顺序、会话成员、未读、审计、直播状态属于 gomob 业务控制面，必须自研并落 PostgreSQL。

音视频编码、拥塞控制、NAT 穿透、多观看者订阅、录制导出属于媒体数据面。这里不自研 RTP / WebRTC SFU，不让 gomob WebSocket 转发媒体。终态选用**自托管 LiveKit**：

- Android 端用 LiveKit Android SDK 连接房间、发布 / 订阅音视频 track。
- 服务端只签发短 TTL room token、记录 room 生命周期、接收 webhook、触发 egress。
- coturn 是正式依赖，用于跨网 / 严格 NAT 场景。

### 2.2 signaling 不再直接承载 SDP / ICE 终态

当前 `server/internal/signaling` 的 `call.invite / call.answer / call.ice` 能验证 WebSocket 长连接、在线邀请和离线 TTL，但它是 P2P 信令模型。

M5 终态中：

- WebSocket 仍保留，用于消息推送、来电邀请、直播状态、批注、预警事件。
- SDP / ICE 由 LiveKit SDK 与 LiveKit Server 处理。
- `call.invite` 语义从“转发 SDP offer”升级为“邀请对方加入一个 gomob media room”。

### 2.3 本地优先，但不伪造在线

消息必须本地 Room 缓存，App 重启后能看到历史和待发送状态。网络恢复后按 `client_msg_id` 幂等补发。

但在线状态、来电、直播观看人数不能本地伪造。离线时 UI 明确显示“未连接实时通道”，只允许查看缓存和发送进入队列。

### 2.4 监管审计是产品能力，不是日志副产物

以下事件必须结构化落库：

- 消息发送 / 撤回 / 已读。
- 通话邀请 / 接听 / 拒绝 / 结束 / 异常断开。
- 直播开始 / 结束 / 加入观看 / 离开观看。
- 语音介入、截图、标记预警、实时批注。
- 录像生成、播放、下载。

日志可以辅助排障，不能替代业务审计。

## 3. 当前现状

| 区域 | 已有 | 缺口 |
|---|---|---|
| App 消息 UI | `feature:message` 有列表、单聊、工具栏骨架 | 全部是静态数据；无 ViewModel / Repository / Room / WebSocket |
| App 协作 UI | `feature:collaboration` 有第一视角列表和观看页 | 视频区域是占位；没有发布端、观看端、语音介入、录制 |
| 服务端消息 | `signaling` 支持 `msg.send / msg.fetch`，`server_seq` 行锁单调，`ws_message_order` harness 覆盖并发顺序 | REST 会话列表 / 历史接口未挂到 `api`；未读、已读、搜索、群聊、系统消息不完整 |
| 服务端通话 | `call.invite / answer / ice / bye` 支持 P2P 信令和离线 invite TTL | 没有媒体 room、call_logs 生命周期闭环、LiveKit token、录制 |
| 媒体基础设施 | registry 里已有 coturn 外部依赖占位 | 没有 LiveKit Server / Egress / webhook / TURN 配置落地 |

## 4. 终态分层

```
Android App
──────────────────────────────────────────────────────────────
feature:message        会话列表 / 单聊 / 通话入口
feature:collaboration  第一视角列表 / 观看页 / 语音介入 / 标预警
        │
        ├── core:data              MessageRepository / LiveSessionRepository
        ├── core:database          conversations / messages / live_sessions 本地缓存
        ├── core:network           REST API + token / gateway endpoint
        ├── core:realtime          OkHttp WebSocket、重连、Envelope、事件分发
        └── core:media             LiveKit Room 包装、track 发布/订阅、质量指标
──────────────────────────────────────────────────────────────
Gateway
──────────────────────────────────────────────────────────────
HTTPS REST: /v1/conversations /v1/messages /v1/media/*
WSS:        /v1/ws
──────────────────────────────────────────────────────────────
服务端控制面
──────────────────────────────────────────────────────────────
api        会话列表、历史、已读、直播列表、房间元数据
signaling  在线状态、消息推送、来电/直播事件、批注事件
media      LiveKit token 签发、room 生命周期、webhook、egress 控制
asset      图片 / 截图 / 视频录像 / 查验资产
audit      结构化审计
──────────────────────────────────────────────────────────────
媒体数据面
──────────────────────────────────────────────────────────────
LiveKit Server + Redis + coturn + Egress → MinIO
```

`media` 可以先落在 `server/internal/signaling` 内作为子包，等接口和部署成熟后再拆 `server/cmd/media`。拆分的判断标准不是“代码多不多”，而是它是否需要独立扩容、独立密钥轮换、独立 webhook 暴露。

## 5. 数据模型

### 5.1 消息控制面

现有表保留：`conversations / conversation_members / messages / pending_calls / call_logs`。

M5 扩展：

```sql
ALTER TABLE conversations ADD COLUMN subject_kind TEXT;      -- none / inspection / review / live_session / online_help
ALTER TABLE conversations ADD COLUMN subject_id BIGINT;      -- 关联业务对象 id
ALTER TABLE conversations ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE messages ADD COLUMN client_msg_id TEXT;
ALTER TABLE messages ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_messages_sender_client_msg
    ON messages(sender_id, client_msg_id)
    WHERE client_msg_id IS NOT NULL;

CREATE TABLE conversation_member_states (
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    last_read_seq   BIGINT NOT NULL DEFAULT 0,
    muted           BOOLEAN NOT NULL DEFAULT false,
    pinned          BOOLEAN NOT NULL DEFAULT false,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(conversation_id, user_id)
);
```

`unread_count` 默认从 `last_read_seq` 之后的他人/系统消息推导；自己发出的消息不制造未读，高并发后再加 Redis 缓存，不先把缓存当真理源。

### 5.2 媒体房间

```sql
CREATE TABLE media_rooms (
    id              BIGSERIAL PRIMARY KEY,
    provider        TEXT NOT NULL DEFAULT 'livekit',
    provider_room   TEXT NOT NULL UNIQUE,
    kind            TEXT NOT NULL, -- call / first_person_live
    subject_kind    TEXT,          -- inspection / review / conversation
    subject_id      BIGINT,
    created_by      BIGINT NOT NULL REFERENCES users(id),
    status          TEXT NOT NULL DEFAULT 'created', -- created / active / ended / failed
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media_participants (
    room_id      BIGINT NOT NULL REFERENCES media_rooms(id) ON DELETE CASCADE,
    user_id      BIGINT NOT NULL REFERENCES users(id),
    role         TEXT NOT NULL, -- publisher / viewer / moderator
    joined_at    TIMESTAMPTZ,
    left_at      TIMESTAMPTZ,
    PRIMARY KEY(room_id, user_id, role)
);
```

### 5.3 第一视角直播

```sql
CREATE TABLE live_sessions (
    id              BIGSERIAL PRIMARY KEY,
    media_room_id   BIGINT NOT NULL REFERENCES media_rooms(id),
    inspection_id   BIGINT REFERENCES inspections(id),
    publisher_id    BIGINT NOT NULL REFERENCES users(id),
    station_id      BIGINT REFERENCES stations(id),
    title           TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'created', -- created / live / ended / failed
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    latest_snapshot_asset_id BIGINT REFERENCES inspection_assets(id),
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE live_annotations (
    id              BIGSERIAL PRIMARY KEY,
    live_session_id BIGINT NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
    author_id       BIGINT NOT NULL REFERENCES users(id),
    kind            TEXT NOT NULL, -- note / warning / screenshot / voice_intervention
    payload         JSONB NOT NULL,
    media_ts_ms     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE live_recordings (
    id              BIGSERIAL PRIMARY KEY,
    live_session_id BIGINT NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
    asset_id        BIGINT REFERENCES inspection_assets(id),
    egress_id       TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'starting', -- starting / active / complete / failed
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    error_message   TEXT
);
```

## 6. 协议

### 6.1 REST

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/v1/conversations?cursor=&limit=` | 会话列表，返回 last_message、unread_count、peer / group 元信息 |
| `GET` | `/v1/conversations/help-experts` | 在线求助固定专家列表，仅用于头像条和专家详情入口 |
| `POST` | `/v1/conversations/help-room` | 创建 / 复用在线求助固定群，成员为当前用户 + 固定专家 |
| `GET` | `/v1/conversations/{id}/messages?since_seq=&limit=&latest=` | 历史消息，升序返回；`latest=true` 返回最新窗口 |
| `POST` | `/v1/conversations/{id}/messages` | HTTP 发送消息，和 WebSocket `msg.send` 共用幂等逻辑 |
| `POST` | `/v1/conversations/{id}/read` | 更新 `last_read_seq` |
| `POST` | `/v1/conversations/{id}/leave` | 当前用户退出群聊，移除成员关系和本地状态 |
| `POST` | `/v1/media/rooms` | 创建 call / first_person_live room |
| `POST` | `/v1/media/rooms/{id}/token` | 为当前用户签发 LiveKit token |
| `POST` | `/v1/media/rooms/{id}/end` | 结束房间 |
| `GET` | `/v1/live-sessions?status=live` | 第一视角在线列表 |
| `POST` | `/v1/live-sessions` | 查验员开始第一视角直播 |
| `POST` | `/v1/live-sessions/{id}/annotations` | 批注 / 标预警 / 截图元数据 |
| `POST` | `/v1/live-sessions/{id}/recordings/start` | 触发 LiveKit Egress |
| `POST` | `/v1/livekit/webhook` | LiveKit webhook，校验签名后入库 |

### 6.2 WebSocket Envelope

现有外壳继续使用：

```json
{ "type": "<事件类型>", "payload": { "...": "..." }, "frame_seq": 12 }
```

M5 新增事件：

| type | 方向 | payload | 说明 |
|---|---|---|---|
| `msg.read` | C→S | `{conversation_id,last_read_seq}` | 已读水位 |
| `msg.read_ack` | S→C | `{conversation_id,last_read_seq,unread_count}` | 已读确认 |
| `presence.update` | S→C | `{user_id,status,last_seen_at}` | 在线状态 |
| `media.invite` | S↔C | `{room_id,kind,from_user_id,to_user_id,conversation_id?}` | 通话 / 直播邀请 |
| `media.invite_ack` | S→C | `{room_id,online,ttl_sec?}` | 邀请投递结果 |
| `media.joined` | S→C | `{room_id,user_id,role}` | 成员加入 |
| `media.left` | S→C | `{room_id,user_id,reason}` | 成员离开 |
| `live.started` | S→C | `{live_session_id,room_id,publisher_id,inspection_id?}` | 第一视角上线 |
| `live.ended` | S→C | `{live_session_id,reason,duration_sec}` | 第一视角结束 |
| `live.annotation` | S↔C | `{live_session_id,kind,payload,media_ts_ms?}` | 批注、预警、截图 |
| `recording.ready` | S→C | `{live_session_id,asset_id,duration_sec}` | 录像可回放 |

旧 `call.invite / call.answer / call.ice / call.bye` 在 M5.4 前保留给 harness。M5.4 后 harness 应迁到 `media.invite` + LiveKit room 生命周期。

## 7. Android 端职责

### 7.1 `core:realtime`

- 持有单条 `/v1/ws?token=` 连接。
- 指数退避重连：1s、2s、4s、8s、16s、30s 上限。
- 重连成功后：
  - 发 `msg.fetch` 或调用 REST 按每个 conversation 的 `last_server_seq` 补齐。
  - 恢复本地未确认的 `pending` 消息发送。
  - 对正在进行的 media room 重新拉 token 并重连。
- 所有 Envelope 解析成 sealed event，feature 不直接读 JSON。

### 7.2 `core:media`

- 封装 LiveKit Room 生命周期：`connect / disconnect / publishCamera / publishMic / subscribeVideo / setSpeaker / collectStats`。
- 默认 1:1 通话发布摄像头 + 麦克风。
- 第一视角直播发布后摄像头，阶段 2 增加屏幕共享，阶段 3 增加 iHawk Color 自定义 track。
- 每 5s 采集一次媒体指标：RTT、packet loss、上行 / 下行 bitrate、视频分辨率、fps；写到 `.dev/` harness 采样时也可上报日志。

### 7.3 `feature:message`

- 会话列表从 Room 读，后台用 REST / WS 同步。
- 输入框发送后立即插入本地 `pending` 消息；收到 `msg.delivered` 后改成 `sent` 并补 `server_seq`。
- 图片、语音、视频片段、截图先走 asset 上传，消息 payload 只保存 asset id / object key；资产上传未接通时只能发送 `media_state=awaiting_asset_upload` 的结构化状态消息，不能伪装成已可播放媒体。
- 视频通话按钮走 `media` 控制面，不再直接拼 SDP。

### 7.4 `feature:collaboration`

- 第一视角列表展示 `live_sessions.status='live'`。
- 观看页使用 LiveKit `VideoTrack` 渲染真实远端画面。
- 底部按钮：
  - 介入语音：当前 viewer 发布 mic track，role 从 viewer 升为 moderator。
  - 切视角：切换 publisher 的 camera / screen / ihawk track。
  - 截图存档：端侧截图上传 asset，并写 `live_annotations.kind='screenshot'`。
  - 标记预警：写 `live_annotations.kind='warning'`，同时推给发布者。
  - 视频通话：在当前 live_session 上创建 side-call room。

## 8. 权限

| 操作 | inspector | supervisor | reviewer | admin |
|---|---:|---:|---:|---:|
| 发送消息 | 是 | 是 | 是 | 是 |
| 创建第一视角直播 | 是 | 否 | 否 | 是 |
| 观看同站直播 | 是 | 是 | 是 | 是 |
| 跨站观看直播 | 否 | 是 | 是 | 是 |
| 语音介入 | 否 | 是 | 是 | 是 |
| 标记预警 | 否 | 是 | 是 | 是 |
| 导出录像 | 否 | 是 | 是 | 是 |

权限在服务端强制校验；端侧只做可用性展示。

## 9. 质量指标

| 能力 | 指标 |
|---|---|
| 消息发送 | 在线场景 P95 delivered ≤ 500ms；弱网重连后消息无重复、无空洞 |
| 消息顺序 | 单会话 `server_seq` 严格单调；并发 100 条无重复、无空洞 |
| 离线补齐 | App 杀进程重启后按本地水位补齐，消息顺序一致 |
| 1:1 通话 | 同局域网首帧 ≤ 2s；跨网 TURN 首帧 ≤ 5s |
| 第一视角直播 | 同站 P95 端到端延迟 ≤ 800ms；跨站 ≤ 1500ms |
| 录制 | 直播结束后 30s 内生成可播放 MP4；asset sha256 与对象存储一致 |
| 审计 | 关键事件 100% 落 audit，缺 audit 视为失败 |

## 10. 风险

| 风险 | 处理 |
|---|---|
| Android WebRTC SDK 与 Compose 生命周期冲突 | `core:media` 把 Room 生命周期绑定到 ViewModel，不在 Composable 里直接持有连接 |
| 检测站网络限制 UDP | coturn 开 TCP/TLS relay；harness 强制 TURN 路径单独测 |
| LiveKit webhook 丢失 | webhook 只做快路径；服务端定期从 RoomService 对账 active room |
| 消息本地缓存与服务端不一致 | 服务端 `server_seq` 是唯一真理；本地按服务端水位重放修正 |
| 录像成本和存储膨胀 | 默认只录第一视角直播；1:1 通话不录，除非监管员显式触发并写审计 |

## 11. 外部资料

- LiveKit Android SDK：`https://docs.livekit.io/reference/client-sdk-android/`
- LiveKit Room Service API：`https://docs.livekit.io/reference/other/roomservice-api/`
- LiveKit Egress：`https://docs.livekit.io/transport/media/ingress-egress/egress/`
- LiveKit Self-hosting：`https://docs.livekit.io/transport/self-hosting/`
- ICE NAT traversal：`https://www.rfc-editor.org/rfc/rfc8445`
- TURN：`https://www.rfc-editor.org/rfc/rfc8656.html`
