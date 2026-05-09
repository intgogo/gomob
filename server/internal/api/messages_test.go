package api

import (
	"encoding/json"
	"testing"
)

func TestValidClientMessageKindIncludesVoice(t *testing.T) {
	for _, kind := range []string{"text", "image", "voice", "video_clip"} {
		if !validClientMessageKind(kind) {
			t.Fatalf("validClientMessageKind(%q)=false, want true", kind)
		}
	}
	if validClientMessageKind("video_call") {
		t.Fatalf("client 不应直接创建 video_call 消息")
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
