#!/usr/bin/env bash
# 验证语音消息转写队列控制面：上传真实音频资产、发送 voice、补建/重试 transcript。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/asr_transcript_queue}"
BASE_URL="${GOMOB_BASE_URL:-http://127.0.0.1:18808}"
mkdir -p "$OUT"

summary="$OUT/summary.json"
step="init"
TRANSCRIPT_TEXT=""

write_summary() {
    local verdict="$1"
    local reason="$2"
    python3 - "$summary" "$verdict" "$reason" "${MESSAGE_ID:-}" "${TRANSCRIPT_STATUS:-}" "${RETRY_STATUS:-}" "${TRANSCRIPT_TEXT:-}" <<'PY'
import json
import sys
path, verdict, reason, message_id, transcript_status, retry_status, transcript_text = sys.argv[1:8]
with open(path, "w", encoding="utf-8") as f:
    json.dump({
        "verdict": verdict,
        "reason": reason,
        "message_id": message_id,
        "transcript_status": transcript_status,
        "retry_status": retry_status,
        "transcript_text": transcript_text,
    }, f, ensure_ascii=False, indent=2)
PY
}

fail() {
    write_summary "abnormal" "$step: $1"
    echo "异常: $step: $1" >&2
    exit 1
}

curl_checked() {
    local method="$1"
    local url="$2"
    local out="$3"
    shift 3
    local code
    code=$(curl -sS -o "$out" -w "%{http_code}" -X "$method" "$url" "$@") || fail "curl 失败 $url"
    case "$code" in
        2*) return 0 ;;
        *) echo "HTTP $code body:" >&2; sed -n '1,120p' "$out" >&2; fail "HTTP $code $url" ;;
    esac
}

step="health"
curl_checked GET "$BASE_URL/healthz" "$OUT/health.txt"

step="login"
curl_checked POST "$BASE_URL/v1/auth/login" "$OUT/login.json" \
    -H "Content-Type: application/json" \
    -d '{"username":"shenhm","password":"shenhm123"}'
TOKEN="$(python3 - "$OUT/login.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["access_token"])
PY
)"

step="generate_audio"
SAMPLE_WAV="$ROOT/.dev/vendor/FireRedASR2S/assets/hello_zh.wav"
if [[ -f "$SAMPLE_WAV" ]]; then
    cp "$SAMPLE_WAV" "$OUT/voice.wav"
else
    python3 - "$OUT/voice.wav" <<'PY'
import sys
import wave
path = sys.argv[1]
rate = 16000
seconds = 1.2
frames = int(rate * seconds)
with wave.open(path, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(rate)
    for i in range(frames):
        import math
        import struct
        sample = int(0.22 * 32767 * math.sin(2 * math.pi * 440 * i / rate))
        w.writeframesraw(struct.pack("<h", sample))
PY
fi
read -r SIZE_BYTES SHA256 < <(python3 - "$OUT/voice.wav" <<'PY'
import hashlib
import sys
data = open(sys.argv[1], "rb").read()
print(len(data), hashlib.sha256(data).hexdigest())
PY
)
DURATION_SEC="$(python3 - "$OUT/voice.wav" <<'PY'
import sys
import wave
with wave.open(sys.argv[1], "rb") as w:
    print(round(w.getnframes() / float(w.getframerate()), 3))
PY
)"

step="asset_upload_init"
curl_checked POST "$BASE_URL/v1/assets/upload/init" "$OUT/upload_init.json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"kind\":\"message_voice\",\"size_bytes\":$SIZE_BYTES,\"sha256\":\"$SHA256\",\"mime\":\"audio/wav\"}"
UPLOAD_ID="$(python3 - "$OUT/upload_init.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["upload_id"])
PY
)"

step="asset_upload_chunk"
curl_checked PUT "$BASE_URL/v1/assets/upload/$UPLOAD_ID/chunk/1" "$OUT/upload_chunk.json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: audio/wav" \
    --data-binary @"$OUT/voice.wav"

step="asset_upload_complete"
curl_checked POST "$BASE_URL/v1/assets/upload/$UPLOAD_ID/complete" "$OUT/upload_complete.json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"total_chunks":1}'
read -r ASSET_ID OBJECT_KEY DOWNLOAD_URL < <(python3 - "$OUT/upload_complete.json" <<'PY'
import json
import sys
data=json.load(open(sys.argv[1], encoding="utf-8"))["data"]
print(data["asset_id"], data["object_key"], data.get("download_url",""))
PY
)

step="draft_transcribe"
curl_checked POST "$BASE_URL/v1/messages/transcribe-draft" "$OUT/draft_transcribe.json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"asset_id\":\"$ASSET_ID\",\"language\":\"zh\"}"
DRAFT_TEXT="$(python3 - "$OUT/draft_transcribe.json" <<'PY'
import json
import sys
data=json.load(open(sys.argv[1], encoding="utf-8"))["data"]
print(data.get("normalized_text") or data.get("text") or "")
PY
)"
[[ "$DRAFT_TEXT" == *"你好世界"* ]] || fail "草稿转写文本=$DRAFT_TEXT，期望包含 你好世界"

step="open_p2p"
curl_checked POST "$BASE_URL/v1/conversations/p2p" "$OUT/conversation.json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"peer_user_id":"2"}'
CONVERSATION_ID="$(python3 - "$OUT/conversation.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["id"])
PY
)"

step="send_voice"
CLIENT_MSG_ID="asr-harness-$(date +%s)"
curl_checked POST "$BASE_URL/v1/conversations/$CONVERSATION_ID/messages" "$OUT/send_voice.json" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"client_msg_id\":\"$CLIENT_MSG_ID\",\"kind\":\"voice\",\"payload\":{\"media_state\":\"ready\",\"asset_id\":\"$ASSET_ID\",\"object_key\":\"$OBJECT_KEY\",\"download_url\":\"$DOWNLOAD_URL\",\"mime\":\"audio/wav\",\"size_bytes\":$SIZE_BYTES,\"sha256\":\"$SHA256\",\"duration_sec\":$DURATION_SEC,\"source\":\"asr_harness\"}}"
read -r MESSAGE_ID TRANSCRIPT_STATUS TRANSCRIPT_ENGINE < <(python3 - "$OUT/send_voice.json" <<'PY'
import json
import sys
data=json.load(open(sys.argv[1], encoding="utf-8"))["data"]
p=data["payload"]
print(data["id"], p.get("transcript_status",""), p.get("transcript_engine",""))
PY
)
[[ "$TRANSCRIPT_STATUS" == "pending" ]] || fail "发送后 transcript_status=$TRANSCRIPT_STATUS，期望 pending"
[[ "$TRANSCRIPT_ENGINE" == "fireredasr2" ]] || fail "transcript_engine=$TRANSCRIPT_ENGINE，期望 fireredasr2"

step="retry"
curl_checked POST "$BASE_URL/v1/messages/$MESSAGE_ID/transcript/retry" "$OUT/retry.json" \
    -H "Authorization: Bearer $TOKEN"
RETRY_STATUS="$(python3 - "$OUT/retry.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["payload"].get("transcript_status",""))
PY
)"
[[ "$RETRY_STATUS" == "pending" ]] || fail "重试后 transcript_status=$RETRY_STATUS，期望 pending"

ASR_HEALTH_URL="${GOMOB_ASR_HEALTH_URL:-http://127.0.0.1:18091/healthz}"
if curl -fsS "$ASR_HEALTH_URL" > "$OUT/asr_health.json" 2>/dev/null; then
    step="wait_done"
    for _ in $(seq 1 45); do
        curl_checked GET "$BASE_URL/v1/conversations/$CONVERSATION_ID/messages" "$OUT/messages_poll.json" \
            -H "Authorization: Bearer $TOKEN"
        TRANSCRIPT_STATUS="$(python3 - "$OUT/messages_poll.json" "$MESSAGE_ID" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]["items"]
message_id = str(sys.argv[2])
for item in data:
    if str(item["id"]) == message_id:
        print(item["payload"].get("transcript_status", ""))
        break
PY
)"
        TRANSCRIPT_TEXT="$(python3 - "$OUT/messages_poll.json" "$MESSAGE_ID" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]["items"]
message_id = str(sys.argv[2])
for item in data:
    if str(item["id"]) == message_id:
        print(item["payload"].get("transcript_normalized_text", ""))
        break
PY
)"
        if [[ "$TRANSCRIPT_STATUS" == "done" ]]; then
            [[ "$TRANSCRIPT_TEXT" == *"你好世界"* ]] || fail "转写文本=$TRANSCRIPT_TEXT，期望包含 你好世界"
            write_summary "normal" "草稿转写和真实语音转写完成"
            echo "正常: draft_text=$DRAFT_TEXT message_id=$MESSAGE_ID transcript_status=$TRANSCRIPT_STATUS text=$TRANSCRIPT_TEXT"
            exit 0
        fi
        [[ "$TRANSCRIPT_STATUS" != "failed" ]] || fail "真实语音转写失败"
        sleep 2
    done
    fail "45 轮内未完成，最后状态 transcript_status=$TRANSCRIPT_STATUS"
fi

write_summary "normal" "语音消息已创建转写任务，重试接口可用"
echo "正常: message_id=$MESSAGE_ID transcript_status=$TRANSCRIPT_STATUS retry_status=$RETRY_STATUS"
