package audit

import (
	"context"
	"strings"
	"testing"
)

func TestEncodeNil(t *testing.T) {
	s, err := Encode(nil)
	if err != nil || s != "" {
		t.Fatalf("nil 应序列化为空串；got %q err=%v", s, err)
	}
}

func TestEncodeStruct(t *testing.T) {
	type V struct {
		A int `json:"a"`
	}
	s, err := Encode(V{A: 7})
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	if !strings.Contains(s, `"a":7`) {
		t.Fatalf("结构未正确序列化: %s", s)
	}
}

func TestInMemoryRecord(t *testing.T) {
	r := &InMemory{}
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if err := r.Record(ctx, Entry{UserID: 1, Action: "test"}); err != nil {
			t.Fatalf("record %d: %v", i, err)
		}
	}
	if got := len(r.Entries); got != 3 {
		t.Fatalf("entries=%d, want 3", got)
	}
	for i, e := range r.Entries {
		if e.CreatedAt.IsZero() {
			t.Fatalf("entry %d 缺 CreatedAt 自动填充", i)
		}
	}
}
