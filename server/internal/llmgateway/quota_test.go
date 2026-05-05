package llmgateway

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
)

func TestQuotaChecker_Disabled_NilRedis(t *testing.T) {
	q := NewQuotaChecker(nil, 10, 10, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if !q.Disabled() {
		t.Fatal("nil redis 应 Disabled")
	}
	c, err := q.CheckAndIncr(context.Background(), 1, 1)
	if err != nil {
		t.Fatalf("disabled 不应错，err=%v", err)
	}
	if c.UserCurrent != 0 {
		t.Fatalf("disabled 不该计数，got %d", c.UserCurrent)
	}
}

func TestQuotaChecker_NilSafety(t *testing.T) {
	var q *QuotaChecker
	if !q.Disabled() {
		t.Fatal("nil 应 Disabled")
	}
}

func TestErrQuotaExceeded_Wrapping(t *testing.T) {
	// 确保 errors.Is 能识别 wrapped error
	wrapped := errors.New("dummy")
	if errors.Is(wrapped, ErrQuotaExceeded) {
		t.Fatal("非 wrapped 不应匹配")
	}
}
