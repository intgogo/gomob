package signaling

import (
	"encoding/json"
	"log/slog"
	"testing"
)

func TestLaserBridgeRoutesPointsToOwner(t *testing.T) {
	hub := NewHub()
	const owner int64 = 42
	c := newTestConn(owner, 4)
	hub.Register(c)
	other := newTestConn(99, 4)
	hub.Register(other)

	b := &LaserBridge{hub: hub, log: slog.Default()}
	raw := []byte(`{"owner_user_id":42,"session_key":"laser-x","unit":0,"points":[1,2,3,4,5,6],"h_angle_deg":12.5}`)
	if d := b.handle(EnvelopeTypeLaserPoints, raw); d != 1 {
		t.Fatalf("delivered=%d 期望 1", d)
	}
	select {
	case env := <-c.send:
		if env.Type != EnvelopeTypeLaserPoints {
			t.Fatalf("type=%s 期望 %s", env.Type, EnvelopeTypeLaserPoints)
		}
		var got map[string]any
		if err := json.Unmarshal(env.Payload, &got); err != nil {
			t.Fatalf("payload 非法: %v", err)
		}
		if got["unit"].(float64) != 0 || got["session_key"] != "laser-x" {
			t.Fatalf("payload 未原样转发: %v", got)
		}
	default:
		t.Fatal("owner 未收到 laser.points")
	}
	select {
	case env := <-other.send:
		t.Fatalf("非 owner 不应收到: %+v", env)
	default:
	}
}

func TestLaserBridgeStatusEnvelopeType(t *testing.T) {
	hub := NewHub()
	const owner int64 = 7
	c := newTestConn(owner, 4)
	hub.Register(c)
	b := &LaserBridge{hub: hub, log: slog.Default()}
	if d := b.handle(EnvelopeTypeLaserStatus, []byte(`{"owner_user_id":7,"session_key":"s","state":"fusing"}`)); d != 1 {
		t.Fatalf("delivered=%d 期望 1", d)
	}
	env := <-c.send
	if env.Type != EnvelopeTypeLaserStatus {
		t.Fatalf("type=%s 期望 %s", env.Type, EnvelopeTypeLaserStatus)
	}
}

func TestLaserBridgeSkipsNoOwner(t *testing.T) {
	b := &LaserBridge{hub: NewHub(), log: slog.Default()}
	if d := b.handle(EnvelopeTypeLaserPoints, []byte(`{"session_key":"s","unit":1,"points":[]}`)); d != 0 {
		t.Fatalf("无 owner 应 0，得 %d", d)
	}
}

func TestLaserBridgeBadJSON(t *testing.T) {
	b := &LaserBridge{hub: NewHub(), log: slog.Default()}
	if d := b.handle(EnvelopeTypeLaserPoints, []byte(`nope`)); d != 0 {
		t.Fatalf("坏 JSON 应 0，得 %d", d)
	}
}
