package audit

import (
	"context"
	"strings"
	"sync"
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
	if got := r.Count(); got != 3 {
		t.Fatalf("entries=%d, want 3", got)
	}
	entries := r.Snapshot()
	for i, e := range entries {
		if e.CreatedAt.IsZero() {
			t.Fatalf("entry %d 缺 CreatedAt 自动填充", i)
		}
	}
	entries[0].Action = "changed-copy"
	first, ok := r.EntryAt(0)
	if !ok || first.Action != "test" {
		t.Fatalf("快照不得修改内部记录: ok=%v entry=%+v", ok, first)
	}
	if _, ok := r.EntryAt(-1); ok {
		t.Fatal("负下标不得读取成功")
	}
	if _, ok := r.EntryAt(3); ok {
		t.Fatal("越界下标不得读取成功")
	}
}

func TestInMemoryConcurrentAccess(t *testing.T) {
	const writers = 4
	const recordsPerWriter = 100
	r := &InMemory{}
	ctx := context.Background()
	var wg sync.WaitGroup
	for writer := 0; writer < writers; writer++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < recordsPerWriter; i++ {
				if err := r.Record(ctx, Entry{UserID: 1, Action: "concurrent"}); err != nil {
					t.Errorf("并发写入失败: %v", err)
					return
				}
			}
		}()
	}
	for reader := 0; reader < writers; reader++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < recordsPerWriter; i++ {
				_ = r.Count()
				entries := r.Snapshot()
				if len(entries) > 0 {
					_, _ = r.EntryAt(len(entries) - 1)
				}
			}
		}()
	}
	wg.Wait()
	if got, want := r.Count(), writers*recordsPerWriter; got != want {
		t.Fatalf("并发写入记录数=%d，期望 %d", got, want)
	}
}
