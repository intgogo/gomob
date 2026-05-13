# 09b — 实时消息与第一视角协作实施文档

> 本文档回答“怎么做”。设计原因与终态边界见
> [09-realtime-message-live.md](09-realtime-message-live.md)。
>
> M5 是涌现行为主线：消息顺序、重连、音视频质量、录制完整性不能只靠单测，必须配 harness。

## 1. 实施顺序

先控制面，后媒体面；先服务端真接口，后端侧替换静态 UI；最后做录制与质量 harness。

```
M5.1 服务端消息 REST + DB 补齐
  ↓
M5.2 Android Room / Repository / WebSocket 客户端
  ↓
M5.3 feature:message 接真实数据
  ↓
M5.4 LiveKit 自托管 + media token 控制面
  ↓
M5.5 1:1 视频通话
  ↓
M5.6 第一视角直播发布 / 观看 / 介入
  ↓
M5.7 录制回放 + 资产归档
  ↓
M5.8 harness 与观测闭环
  ↓
M5.9 语音消息转文字
```

## 2. M5.1 服务端消息控制面

### 2.1 迁移

新增 migration `server/migrations/0010_realtime_message_live.up.sql`：

- 扩展 `conversations`：`subject_kind / subject_id / updated_at`。
- 扩展 `messages`：`client_msg_id / edited_at / deleted_at`。
- 新建 `conversation_member_states`。
- 新建 `media_rooms / media_participants / live_sessions / live_annotations / live_recordings`。
- 新增 `server/migrations/0011_help_room.up.sql`：给 `conversations(subject_kind, subject_id)` 建 partial unique index，保证每个发起人只有一个 `subject_kind=online_help / subject_id=user_id` 的固定求助群。

Down migration 必须按 FK 反向删除。

### 2.2 Repo

扩展 `server/pkg/repo/messages.go`：

- `ListConversationsForUser(ctx, userID, cursor, limit)`：返回会话、成员、最后一条消息、未读数。
- `AppendIdempotent(ctx, msg, clientMsgID)`：同一 `sender_id + client_msg_id` 重发返回原消息，不重新分配 `server_seq`。
- `MarkRead(ctx, conversationID, userID, lastReadSeq)`：只允许成员更新自己的已读水位。
- `UnreadCount(ctx, conversationID, userID)`：只统计 `last_read_seq` 之后的他人/系统消息，自己发出的消息不制造未读。
- `EnsureMemberState(ctx, conversationID, userID)`：创建会话成员时同步建 state。
- `GetOrCreateSubjectGroup(ctx, title, subjectKind, subjectID, memberIDs)`：按业务 subject 创建 / 复用固定群会话，并补齐成员和已读状态。

### 2.3 API

在 `server/internal/api/handler.go` 挂载：

- `GET /v1/conversations`
- `GET /v1/conversations/help-experts`
- `POST /v1/conversations/help-room`
- `GET /v1/conversations/{id}/messages`
- `POST /v1/conversations/{id}/messages`
- `POST /v1/conversations/{id}/read`
- `POST /v1/conversations/{id}/leave`

返回格式遵守 `docs/architecture/server/02-api-contract.md`：int64 id 用字符串。
`help-room` 是在线求助的固定多人聊天窗口：当前用户 + 固定专家加入同一个 `kind=group` 会话，App 只向该群发送消息，不再向每个专家拆分 P2P 消息。
`leave` 只允许群聊；成功后删除当前用户的 `conversation_members` 和 `conversation_member_states`，会话历史继续保留给其它成员。

### 2.4 验收

- `go test ./pkg/repo -run 'Conversation|Message'` 通过。
- 扩展 `tests/harness/ws_message_order`：
  - S16：同一个 `client_msg_id` 连发两次，只出现一个 `server_seq`。
  - S17：`POST /v1/conversations/{id}/read` 后 `unread_count=0`。
  - S18：`GET /v1/conversations` 返回 last_message 与服务端最新消息一致。
  - S18b：发送方会话列表 `unread_count=0`，自己发出的消息不制造未读。

## 3. M5.2 Android 实时数据层

### 3.1 模块

新增：

- `:core:realtime`：WebSocket 连接、Envelope、重连、事件流。
- `:core:media`：M5.4 再接 LiveKit；M5.2 只定义接口契约，不提供假媒体实现，也不让 UI 调用不可用实现。

同步更新：

- `settings.gradle.kts`
- `docs/architecture/registry/modules.yaml`
- `docs/architecture/registry/dependencies.yaml`
- `gradle/libs.versions.toml`

### 3.2 本地数据库

`core:database` 当前只有 manifest，需要正式引入 Room database：

- `GomobDatabase`
- `ConversationEntity`
- `MessageEntity`
- `ConversationMemberStateEntity`
- `LiveSessionEntity`
- DAO：
  - `ConversationDao.observeConversations()`
  - `MessageDao.observeMessages(conversationId)`
  - `MessageDao.upsertServerMessages(items)`
  - `MessageDao.insertPending(message)`
  - `MessageDao.markDelivered(clientMsgId, messageId, serverSeq)`
  - `ConversationDao.markRead(conversationId, lastReadSeq)`

### 3.3 Repository

`core:data` 新增：

- `MessageRepository`
- `RealtimeRepository`
- `LiveSessionRepository`

发送消息流程：

1. 生成 `client_msg_id=UUID`。
2. 本地插入 `status=pending`。
3. 优先走 WebSocket `msg.send`。
4. WebSocket 不在线时走 HTTP；HTTP 也失败则保留 pending。
5. 收到 delivered 后按 `client_msg_id` 更新服务端 id / seq。

服务端 HTTP 写入不是降级成“只等刷新”：在 devserver 合体拓扑中，API 写库后通过 `RealtimeMessageNotifier` 复用 signaling 的 `msg.recv` 推送；只对幂等新插入消息推送，`client_msg_id` 重试不重复通知。拆分部署需要在 M5.8 前补 NATS/跨进程桥接保持同语义。

### 3.4 验收

- `./dev.sh test` 通过。
- 新增 Android host 单测：
  - `RealtimeEnvelopeParserTest`：未知 type 不崩，进入 `UnknownEvent`。
  - `MessageRepositoryTest`：pending → delivered 状态迁移不重复插入。
- `tests/harness/realtime_message_sync`：
  - 起 gateway/api/signaling。
  - Android 或 JVM harness 模拟断网重连。
  - 输出 `.dev/realtime_message_sync/results.jsonl`。

## 4. M5.3 消息 UI 真实化

### 4.1 `feature:message`

新增 ViewModel：

- `MessageViewModel`
- `ConversationViewModel`

删除静态数据：

- `MessageScreen.kt` 的 `MESSAGES`
- `ConversationScreen.kt` 的 `DEMO_BUBBLES`

UI 状态：

- `Loading`
- `Content`
- `Empty`
- `OfflineCached`
- `Error(traceId/message)`

### 4.2 行为

- 会话列表实时更新 last_message / unread_count。
- 单聊进入时加载历史并自动 `markRead(lastVisibleSeq)`。
- 发送文本后立刻出现 pending 气泡；失败显示重试状态。
- 图片 / 拍摄按钮接 asset 上传，不上传假图片。
- 语音 / 视频消息发送结构化 `voice` / `video_clip` 控制消息，payload 标记 `media_state=ready` 后才落正式消息；真实录音、视频文件可播放前必须接通 asset 上传和下载 URL，不在 UI 里伪装已上传媒体。
- 语音消息 payload 可携带 `transcript_status / transcript_normalized_text / transcript_error`；气泡展示转写中、结果、失败状态，会话列表 last_message 使用服务端转写摘要。
- 视频通话按钮在 M5.5 前 disabled，显示真实不可用状态，不写 stub。

### 4.3 UI 验收

- `./dev.sh run`
- `uiautomator dump` / instrumentation 覆盖消息列表与会话页。
- 验收要求：
  - 无静态假数据。
  - 离线提示状态可达，输入框仍可聚焦。
  - pending / sent / failed 状态可读且可由测试断言。

## 5. M5.4 LiveKit 媒体控制面

### 5.1 服务端

先在 `server/internal/signaling/media/` 或 `server/internal/media/` 实现：

- `POST /v1/media/rooms`
- `POST /v1/media/rooms/{id}/token`
- `POST /v1/media/rooms/{id}/end`
- `POST /v1/livekit/webhook`

环境变量：

- `GOMOB_LIVEKIT_URL`
- `GOMOB_LIVEKIT_API_KEY`
- `GOMOB_LIVEKIT_API_SECRET`
- `GOMOB_TURN_URL`

Token 规则：

- TTL 默认 10 分钟。
- room 名由服务端生成：`gomob_<kind>_<ulid>`。
- participant identity = `user:<id>`。
- metadata 写 role、station_id、subject_kind、subject_id。

### 5.2 端侧

`gradle/libs.versions.toml` 新增 LiveKit Android SDK 版本，`core:media` 依赖它。

`core:media` 暴露：

```kotlin
interface MediaRoomClient {
    val state: StateFlow<MediaRoomState>
    suspend fun connect(roomId: String, token: String)
    suspend fun publishCamera(enabled: Boolean)
    suspend fun publishMicrophone(enabled: Boolean)
    suspend fun disconnect(reason: String)
}
```

### 5.3 验收

新增 `tests/harness/livekit_room_lifecycle`：

- 启动 LiveKit dev server、gateway、api/signaling。
- 创建 room。
- 签发 publisher / viewer token。
- 两个测试客户端加入同一 room。
- 断开后服务端 room status 变 `ended`。

## 6. M5.5 视频通话

### 6.1 服务端语义

`media.invite` 替代旧 P2P SDP：

```json
{
  "room_id": "123",
  "kind": "call",
  "from_user_id": "1",
  "to_user_id": "2",
  "conversation_id": "9"
}
```

被叫在线：WebSocket 推邀请。

被叫离线：写 pending media invite，默认 60s TTL。M5 阶段仍不接 FCM / 厂商推送；杀后台来电不可用是显式限制。

通话结束：

- 更新 `media_rooms.ended_at/status`。
- 写 `call_logs`。
- 在对应 conversation 里追加 `kind=video_call` 消息，payload 含 duration、room_id、status。

### 6.2 Android

- 单聊工具栏视频按钮创建 call room。
- 通话页全屏远端视频，角落本地预览。
- 挂断、静音、摄像头开关。
- 来电弹层只在 App 前台 / WebSocket 在线时显示。

### 6.3 验收

- `tests/harness/livekit_call_quality`：
  - 同机两个客户端模拟加入。
  - 首帧时间 ≤ 2s。
  - 挂断后 conversation 出现 `video_call` 消息。
- uiautomator / instrumentation 确认远端视频容器、本地预览、挂断 / 静音 / 摄像头按钮存在且可点击。

## 7. M5.6 第一视角直播

### 7.1 发布端

查验员从 `feature:scan3d` 或 `feature:collaboration` 发起：

- 创建 `live_session`。
- 连接 LiveKit room。
- 发布后摄像头 + 麦克风可选。
- 后续阶段增加屏幕共享和 iHawk Color 自定义 track。

### 7.2 观看端

`feature:collaboration`：

- 第一视角列表接 `GET /v1/live-sessions?status=live`。
- 观看页订阅 publisher 的 video track。
- 右侧指标来自 live session 元数据和 media stats。
- 批注 / 标预警走 WebSocket `live.annotation`，并同步 REST 入库。

### 7.3 验收

- `tests/harness/first_person_live_quality`：
  - publisher 发布 720p/15fps 测试视频源。
  - viewer 收到非空视频帧。
  - P95 延迟 ≤ 1500ms。
  - 标预警后 publisher 收到 `live.annotation`。
- uiautomator / logcat 确认直播列表可进入观看页，观看页已订阅真实 video track。

## 8. M5.7 录制与回放

### 8.1 服务端

- `POST /v1/live-sessions/{id}/recordings/start` 调 LiveKit Egress。
- webhook 收到 egress complete 后：
  - 把 MP4/HLS 对象登记为 asset。
  - 写 `live_recordings.status='complete'`。
  - 推 WebSocket `recording.ready`。

### 8.2 Android

- 协作页“近期录像”从 `live_recordings` 拉真实数据。
- 播放使用系统播放器或后续 ExoPlayer；M5.7 先保证可打开播放。

### 8.3 验收

`tests/harness/live_recording_egress`：

- 10s 测试直播。
- 触发 egress。
- 30s 内拿到 MP4。
- sha256 与 asset 表一致。

## 9. M5.8 观测

新增日志字段：

- `room_id`
- `live_session_id`
- `conversation_id`
- `client_msg_id`
- `server_seq`
- `media_rtt_ms`
- `packet_loss_pct`
- `bitrate_kbps`
- `first_frame_ms`

所有 harness 产物写 `.dev/<harness-name>/`，并由 `analyze.py` 输出“正常 / 警告 / 异常 + 原因”。

## 10. M5.9 语音消息转文字

### 10.1 算法与服务

准确优先默认采用自托管 `FireRedASR2-AED + FireRedVAD + FireRedLID + FireRedPunc`。选择理由：

- FireRedASR2-AED 支持词级时间戳和置信度，便于低置信片段提示人工确认。
- 普通话公开集平均 CER 约 3.05%，方言/口音公开集平均 CER 约 11.67%；LLM 分支更低但算力更重，作为后续 A/B 对照。
- 原始语音仍是事实源；转写是派生内容，可失败、可重试，不覆盖原消息。

服务入口：

- `server/asr_service/app.py`：FastAPI 封装 FireRedASR2S 官方 Python API，模型缺失时启动失败，不返回假文本。
- `POST /v1/asr/transcribe`：multipart 上传音频，服务内用 ffmpeg 转 16kHz 16-bit mono PCM wav。
- `server/cmd/asrworker` 或 devserver 内置 worker：从 `message_transcripts` 领取任务，拉 MinIO 音频，调用 ASR 服务，完成后写回消息 payload 并推 `msg.transcript.updated`。

### 10.2 数据模型

新增 `message_transcripts`：

- `message_id` 唯一，避免同一语音重复生成多个正式转写。
- `status = pending / processing / done / failed`。
- 记录 `engine / model / language / confidence / segments / error_message / attempt_count`，用于质量追溯和重试。

语音消息 payload 同步冗余展示字段：

- `transcript_status`
- `transcript_engine`
- `transcript_model`
- `transcript_language`
- `transcript_text`
- `transcript_normalized_text`
- `transcript_segments`
- `transcript_confidence`
- `transcript_error`

### 10.3 Android

- `core:realtime` 解析 `msg.transcript.updated`。
- `core:data` 更新本地 Room 消息 payload、preview 和会话摘要。
- `feature:message` 在语音气泡内展示转写状态和文本。
- `core:network` 暴露 `POST /v1/messages/{id}/transcript/retry` 供失败重试。

### 10.4 验收

- 无模型环境：发送语音消息后服务端返回 payload 含 `transcript_status=pending`，重试接口返回 200。
- 有模型环境：30 秒内单条语音不超过 2 秒返回；普通短语音平均 CER <= 5%，业务关键词召回率 >= 98%。
- 低置信 VIN / 车牌 / 型号字段不得自动定稿，必须在 UI 上保留人工确认空间。
