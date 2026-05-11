# asr_transcript_queue

验证语音转文字控制面，不依赖真实 ASR 模型：

1. 生成 16kHz mono wav 测试音频。
2. 走 `/v1/assets/upload/*` 上传为 `message_voice` 资产。
3. 发送 `kind=voice` 消息。
4. 校验返回 payload 含 `transcript_status=pending`、`transcript_engine=fireredasr2`。
5. 调用 `POST /v1/messages/{id}/transcript/retry`，确认重试可补回 pending。

运行：

```bash
./dev.sh harness asr_transcript_queue
```

前置：本地 devserver 在 `:18808`，PG/Redis/MinIO 可用并已应用迁移。
