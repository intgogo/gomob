package api

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"io.gomob/server/pkg/repo"
)

func TestValidClientMessageKindIncludesVoice(t *testing.T) {
	for _, kind := range []string{"text", "image", "voice", "video_clip", "inspection_card"} {
		if !validClientMessageKind(kind) {
			t.Fatalf("validClientMessageKind(%q)=false, want true", kind)
		}
	}
	if validClientMessageKind("video_call") {
		t.Fatalf("client 不应直接创建 video_call 消息")
	}
	if validClientMessageKind("call_invite") {
		t.Fatalf("client 不应绕过控制面直接创建 call_invite 消息")
	}
}

func TestMessagePreviewVoiceAwaitingUpload(t *testing.T) {
	payload := json.RawMessage(`{"media_state":"awaiting_asset_upload","duration_sec":0}`)
	if got, want := messagePreview("voice", payload), "[语音待上传]"; got != want {
		t.Fatalf("voice preview=%q want %q", got, want)
	}
}

func TestMessagePreviewVideoClipAwaitingUpload(t *testing.T) {
	payload := json.RawMessage(`{"media_state":"awaiting_asset_upload"}`)
	if got, want := messagePreview("video_clip", payload), "[视频待上传]"; got != want {
		t.Fatalf("video preview=%q want %q", got, want)
	}
}

func TestMessagePreviewInspectionCard(t *testing.T) {
	payload := json.RawMessage(`{"vin":"LSVHM133022221761"}`)
	if got, want := messagePreview("inspection_card", payload), "[流水] LSVHM133022221761"; got != want {
		t.Fatalf("inspection card preview=%q want %q", got, want)
	}
}

func TestMessagePreviewCallInvite(t *testing.T) {
	payload := json.RawMessage(`{"title":"和陈若愚的视频通话"}`)
	if got, want := messagePreview("call_invite", payload), "[视频通话] 和陈若愚的视频通话"; got != want {
		t.Fatalf("call invite preview=%q want %q", got, want)
	}
}

func TestMessagePreviewCallInviteCompletedShowsDuration(t *testing.T) {
	payload := json.RawMessage(`{"title":"和陈若愚的视频通话","status":"completed","duration_sec":75}`)
	if got, want := messagePreview("call_invite", payload), "[和陈若愚的视频通话 1:15]"; got != want {
		t.Fatalf("call invite completed preview=%q want %q", got, want)
	}
}

func TestMessagePreviewCallInviteFailedShowsReason(t *testing.T) {
	payload := json.RawMessage(`{"title":"和陈若愚的视频通话","status":"failed","reason":"LiveKit token 过期"}`)
	if got, want := messagePreview("call_invite", payload), "[和陈若愚的视频通话失败] LiveKit token 过期"; got != want {
		t.Fatalf("call invite failed preview=%q want %q", got, want)
	}
}

func TestMessagePreviewVideoCallCompletedShowsDuration(t *testing.T) {
	payload := json.RawMessage(`{"status":"completed","duration_sec":75}`)
	if got, want := messagePreview("video_call", payload), "[视频通话 1:15]"; got != want {
		t.Fatalf("video call preview=%q want %q", got, want)
	}
}

func TestMessagePreviewVideoCallFailedShowsReason(t *testing.T) {
	payload := json.RawMessage(`{"status":"failed","reason":"LiveKit token 过期"}`)
	if got, want := messagePreview("video_call", payload), "[视频通话失败] LiveKit token 过期"; got != want {
		t.Fatalf("video call preview=%q want %q", got, want)
	}
}

func TestToLastMessageDTOCarriesClientMsgID(t *testing.T) {
	senderID := int64(2)
	clientMsgID := "echo-1"
	dto := toLastMessageDTO(&repo.Message{
		ID:             101,
		ConversationID: 9,
		SenderID:       &senderID,
		ServerSeq:      5,
		Kind:           "text",
		Payload:        json.RawMessage(`{"text":"详情页打开前发送"}`),
		ClientMsgID:    &clientMsgID,
		CreatedAt:      time.Date(2026, 5, 8, 12, 0, 1, 0, time.UTC),
	})

	if got, want := dto.ClientMsgID, clientMsgID; got != want {
		t.Fatalf("last_message client_msg_id=%q want %q", got, want)
	}
}

func TestDeleteConversationDTOContract(t *testing.T) {
	body, err := json.Marshal(deleteConversationDTO{
		ConversationID:   "42",
		DeletedBeforeSeq: 8,
	})
	if err != nil {
		t.Fatalf("序列化删除会话响应失败: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("解析删除会话响应失败: %v", err)
	}
	if got["conversation_id"] != "42" || got["deleted_before_seq"] != float64(8) {
		t.Fatalf("删除会话响应契约错误: %s", body)
	}
}

func TestNotifyRealtimeMessageOnlyForInsertedMessages(t *testing.T) {
	notifier := &fakeRealtimeNotifier{}
	h := &Handler{realtime: notifier}
	msg := &repo.Message{ID: 7, ConversationID: 9, ServerSeq: 3}

	h.notifyRealtimeMessage(context.Background(), 1, msg, false)
	if notifier.calls != 0 {
		t.Fatalf("idempotent replay 不应重复实时推送，calls=%d", notifier.calls)
	}

	h.notifyRealtimeMessage(context.Background(), 1, msg, true)
	if notifier.calls != 1 {
		t.Fatalf("新消息应触发一次实时推送，calls=%d", notifier.calls)
	}
	if notifier.senderID != 1 || notifier.message != msg {
		t.Fatalf("推送参数不正确 sender=%d message=%p", notifier.senderID, notifier.message)
	}
}

func TestNotifyRealtimeMessageIgnoresNotifierError(t *testing.T) {
	notifier := &fakeRealtimeNotifier{err: errors.New("push failed")}
	h := &Handler{realtime: notifier}

	h.notifyRealtimeMessage(context.Background(), 1, &repo.Message{ID: 7, ConversationID: 9, ServerSeq: 3}, true)
	if notifier.calls != 1 {
		t.Fatalf("应尝试实时推送，calls=%d", notifier.calls)
	}
}

type fakeRealtimeNotifier struct {
	calls    int
	senderID int64
	message  *repo.Message
	err      error
}

func (f *fakeRealtimeNotifier) NotifyMessage(_ context.Context, senderID int64, message *repo.Message) (int, error) {
	f.calls++
	f.senderID = senderID
	f.message = message
	if f.err != nil {
		return 0, f.err
	}
	return 1, nil
}
