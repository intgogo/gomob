# Android 实时 WS 与 devserver 合体服务注意点

## Why

Android App 侧 OkHttp `newWebSocket` 需要传入 `http/https` URL，不能用 `HttpUrl.Builder.scheme("ws")`；否则登录后启动实时通道会崩溃。

devserver 若用访问日志包装 `ResponseWriter`，必须透传 `http.Hijacker`，否则 `/v1/ws` 在合体服务中升级 WebSocket 会返回 500。独立 signaling/gateway harness 不会暴露这个问题。

HTTP fallback 发消息也必须进入同一条实时投递链：`POST /v1/conversations/{id}/messages` / `call-invites` 写库成功后，通过 API 注入的 `RealtimeMessageNotifier` 调 `signaling.Router.NotifyMessage`，只对 `AppendIdempotent(inserted=true)` 的新消息推 `msg.recv`，避免 `client_msg_id` 重试重复来信。devserver 同进程已接好；拆分部署后需要 NATS/跨进程桥接补同语义。

聊天语音 / 图片 / 视频片段上传属于通用消息资产，不一定绑定查验流水。开发库必须应用 `server/migrations/0011_message_assets.up.sql`，让 `inspection_assets.inspection_id` 可空；否则 `/v1/assets/upload/{id}/complete` 会因 `inspection_id NULL` 返回 500。

本地视频通话必须同时满足两件事：LiveKit dev server 在宿主机 `:7880` 运行，devserver 带 `GOMOB_LIVEKIT_URL=ws://127.0.0.1:7880 GOMOB_LIVEKIT_API_KEY=devkey GOMOB_LIVEKIT_API_SECRET=secret` 启动。真机 / 模拟器用 `adb reverse tcp:7880 tcp:7880` 让 App 访问同一个 LiveKit URL。

## How to apply

- App 侧 `RealtimeSocketClient` 构造 WebSocket URL 时使用 `scheme("http")`，OkHttp 会完成 WebSocket Upgrade。
- 修改 devserver 中间件时，包装器要实现并委托 `Hijack()`；涉及流式/WS 的路径不要只用普通 `ResponseWriter`。
- 验证优先看 logcat：`RealtimeSocketClient` 应出现“实时通道已连接”，`MessageRepository` 应出现 `实时 hello user_id=...`。
- `device_realtime_interaction` 默认跑 devserver 合体拓扑，并用 D7 验证 HTTP fallback 写入后在线对端收到 `msg.recv`。
- 语音报“服务端内部错误”时先查 devserver/asset 日志；若看到 `CreateInspectionAsset 失败 ... inspection_id ... violates not-null`，应用 0011。
- LiveKit 本地开发按官方 `--dev` 语义使用 `devkey/secret`；harness 可通过导出 `GOMOB_LIVEKIT_*` 让 L1 输出 `livekit_configured:true` / `status:active`。
