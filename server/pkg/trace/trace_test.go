package trace

import (
	"context"
	"errors"
	"testing"
)

func TestNoopByDefault(t *testing.T) {
	ctx := context.Background()
	tr := FromContext(ctx)
	if tr == nil {
		t.Fatal("FromContext 不应返回 nil")
	}
	_, span := tr.Start(ctx, "anything")
	span.SetAttribute("k", "v")
	span.RecordError(errors.New("ignored"))
	span.End() // 全部应是 no-op，不 panic 即通过
}

func TestRecording(t *testing.T) {
	r := &Recording{}
	ctx := WithTracer(context.Background(), r)

	tr := FromContext(ctx)
	_, s1 := tr.Start(ctx, "step.one")
	s1.SetAttribute("k1", 7)
	s1.End()

	_, s2 := tr.Start(ctx, "step.two")
	s2.RecordError(errors.New("boom"))
	s2.End()

	if len(r.Spans) != 2 {
		t.Fatalf("spans=%d, want 2", len(r.Spans))
	}
	if r.Spans[0].Name != "step.one" || !r.Spans[0].Ended {
		t.Errorf("first span: %+v", r.Spans[0])
	}
	if r.Spans[0].Attributes["k1"] != 7 {
		t.Errorf("attribute lost: %+v", r.Spans[0].Attributes)
	}
	if len(r.Spans[1].Errors) != 1 {
		t.Errorf("error not recorded: %+v", r.Spans[1])
	}
}
