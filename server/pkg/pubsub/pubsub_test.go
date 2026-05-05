package pubsub

import (
	"context"
	"encoding/json"
	"testing"
)

func TestInMemoryPublish(t *testing.T) {
	p := &InMemory{}
	ctx := context.Background()
	if err := p.Publish(ctx, "model.version.activated",
		map[string]any{"name": "yolo", "version": "v1"}); err != nil {
		t.Fatal(err)
	}
	if err := p.Publish(ctx, "inspection.created",
		map[string]any{"id": 7}); err != nil {
		t.Fatal(err)
	}
	if got := len(p.Events); got != 2 {
		t.Fatalf("events=%d want 2", got)
	}
	if p.Events[0].Topic != "model.version.activated" {
		t.Errorf("topic[0]=%q", p.Events[0].Topic)
	}
	var payload map[string]any
	if err := json.Unmarshal(p.Events[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["name"] != "yolo" {
		t.Errorf("payload bad: %v", payload)
	}
}
