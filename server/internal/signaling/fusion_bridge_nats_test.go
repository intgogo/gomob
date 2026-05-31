//go:build e2e_fusion

// 真 NATS 往返测(需 NATS 在跑;由 tests/harness/scan_fusion_e2e/run.sh 驱动):
// 验证 StartFusionBridge 的订阅 + 异步投递 wiring —— 发 scan.fusion_done → owner ws 连接收到。
package signaling

import (
	"encoding/json"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/nats-io/nats.go"
)

func TestFusionBridgeNATSRoundtrip(t *testing.T) {
	url := os.Getenv("GOMOB_NATS_URL")
	if url == "" {
		t.Skip("缺 GOMOB_NATS_URL")
	}
	nc, err := nats.Connect(url)
	if err != nil {
		t.Fatalf("连 NATS: %v", err)
	}
	defer nc.Close()

	hub := NewHub()
	const owner int64 = 777
	c := newTestConn(owner, 4)
	hub.Register(c)

	bridge, err := StartFusionBridge(nc, hub, slog.Default())
	if err != nil {
		t.Fatalf("启动 bridge: %v", err)
	}
	defer bridge.Close()

	evt, _ := json.Marshal(map[string]any{
		"job_id": 1, "session_key": "rt-1", "owner_user_id": owner,
		"result_object_key": "scan_fusion/rt-1/result.glb", "vertices": 1000,
	})
	if err := nc.Publish(topicFusionDone, evt); err != nil {
		t.Fatalf("publish: %v", err)
	}
	_ = nc.Flush()

	select {
	case env := <-c.send:
		if env.Type != EnvelopeTypeFusionDone {
			t.Fatalf("type=%s 期望 %s", env.Type, EnvelopeTypeFusionDone)
		}
		t.Logf("✓ 经 NATS 往返收到 scan.fusion_done: %s", env.Payload)
	case <-time.After(3 * time.Second):
		t.Fatal("3s 内未经 NATS 收到 scan.fusion_done")
	}

	// 幂等:重复 Close 不应 panic(sync.Once 守卫真实 *nats.Subscription)。
	bridge.Close()
	bridge.Close()
}
