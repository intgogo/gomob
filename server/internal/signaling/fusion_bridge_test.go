package signaling

import (
	"encoding/json"
	"log/slog"
	"testing"
)

// newTestConn 构造一个不绑真实 ws 的 Conn(仅用 send/closed chan 做投递断言)。
// Send 成功路径不触碰 ws 字段;只要 send buf 不满就安全。
func newTestConn(userID int64, buf int) *Conn {
	return &Conn{
		UserID: userID,
		send:   make(chan Envelope, buf),
		closed: make(chan struct{}),
		log:    slog.Default(),
	}
}

func TestFusionBridgeRoutesToOwner(t *testing.T) {
	hub := NewHub()
	const owner int64 = 42
	c := newTestConn(owner, 4)
	hub.Register(c)
	other := newTestConn(99, 4) // 同时在线的别的用户,不该收到
	hub.Register(other)

	b := &FusionBridge{hub: hub, log: slog.Default()}
	raw := []byte(`{"job_id":7,"session_key":"s1","owner_user_id":42,` +
		`"result_object_key":"scan_fusion/s1/result.glb","vertices":3594,"triangles":7000,"frame_count":8}`)
	if d := b.handle(raw); d != 1 {
		t.Fatalf("delivered=%d 期望 1(只投递给 owner)", d)
	}

	select {
	case env := <-c.send:
		if env.Type != EnvelopeTypeFusionDone {
			t.Fatalf("type=%s 期望 %s", env.Type, EnvelopeTypeFusionDone)
		}
		var got map[string]any
		if err := json.Unmarshal(env.Payload, &got); err != nil {
			t.Fatalf("payload 非法 JSON: %v", err)
		}
		if got["result_object_key"] != "scan_fusion/s1/result.glb" || got["session_key"] != "s1" {
			t.Fatalf("payload 字段未原样转发: %v", got)
		}
	default:
		t.Fatal("owner 连接未收到 scan.fusion_done")
	}

	select {
	case env := <-other.send:
		t.Fatalf("非 owner 连接不应收到: %+v", env)
	default:
	}
}

func TestFusionBridgeSkipsWhenNoOwner(t *testing.T) {
	b := &FusionBridge{hub: NewHub(), log: slog.Default()}
	if d := b.handle([]byte(`{"session_key":"s1","result_object_key":"k"}`)); d != 0 {
		t.Fatalf("无 owner 应不推送,delivered=%d", d)
	}
}

func TestFusionBridgeOwnerOffline(t *testing.T) {
	b := &FusionBridge{hub: NewHub(), log: slog.Default()}
	if d := b.handle([]byte(`{"owner_user_id":1234,"session_key":"s1"}`)); d != 0 {
		t.Fatalf("owner 离线应 delivered=0,得 %d", d)
	}
}

func TestFusionBridgeBadJSON(t *testing.T) {
	b := &FusionBridge{hub: NewHub(), log: slog.Default()}
	if d := b.handle([]byte(`not json`)); d != 0 {
		t.Fatalf("坏 JSON 应 delivered=0,得 %d", d)
	}
}
