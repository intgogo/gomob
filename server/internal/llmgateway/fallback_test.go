package llmgateway

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
)

// fakeProvider 测试用 provider — Chat / ChatStream 行为可参数化。
type fakeProvider struct {
	name    string
	chatFn  func(ctx context.Context, req ChatRequest) (string, Usage, error)
	streamFn func(ctx context.Context, req ChatRequest, onChunk func(Chunk) bool) (Usage, error)
}

func (f *fakeProvider) Name() string { return f.name }
func (f *fakeProvider) Chat(ctx context.Context, req ChatRequest) (string, Usage, error) {
	return f.chatFn(ctx, req)
}
func (f *fakeProvider) ChatStream(ctx context.Context, req ChatRequest, onChunk func(Chunk) bool) (Usage, error) {
	return f.streamFn(ctx, req, onChunk)
}

func quietLog() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestFallback_Chat_PrimaryOK_NoFallback(t *testing.T) {
	primary := &fakeProvider{
		name: "primary",
		chatFn: func(ctx context.Context, req ChatRequest) (string, Usage, error) {
			return "primary-resp", Usage{Model: "primary"}, nil
		},
	}
	called2 := false
	secondary := &fakeProvider{
		name: "secondary",
		chatFn: func(ctx context.Context, req ChatRequest) (string, Usage, error) {
			called2 = true
			return "should-not-be-called", Usage{}, nil
		},
	}
	fp := NewFallbackProvider(quietLog(), primary, secondary)
	got, _, err := fp.Chat(context.Background(), ChatRequest{User: "x"})
	if err != nil || got != "primary-resp" {
		t.Fatalf("primary 应直接返，got %q err=%v", got, err)
	}
	if called2 {
		t.Fatal("primary OK 时不该调用 secondary")
	}
}

func TestFallback_Chat_PrimaryFail_FallbackToSecondary(t *testing.T) {
	primary := &fakeProvider{
		name: "primary",
		chatFn: func(ctx context.Context, req ChatRequest) (string, Usage, error) {
			return "", Usage{}, ErrProviderUnavailable
		},
	}
	secondary := &fakeProvider{
		name: "secondary",
		chatFn: func(ctx context.Context, req ChatRequest) (string, Usage, error) {
			return "secondary-resp", Usage{Model: "secondary"}, nil
		},
	}
	fp := NewFallbackProvider(quietLog(), primary, secondary)
	got, _, err := fp.Chat(context.Background(), ChatRequest{User: "x"})
	if err != nil {
		t.Fatalf("应 fallback 成功，err=%v", err)
	}
	if got != "secondary-resp" {
		t.Fatalf("期望 secondary 输出，got %q", got)
	}
}

func TestFallback_Chat_AllFail_ReturnsLastErr(t *testing.T) {
	a := &fakeProvider{name: "a", chatFn: func(_ context.Context, _ ChatRequest) (string, Usage, error) {
		return "", Usage{}, ErrProviderUnavailable
	}}
	b := &fakeProvider{name: "b", chatFn: func(_ context.Context, _ ChatRequest) (string, Usage, error) {
		return "", Usage{}, ErrProviderRateLimit
	}}
	fp := NewFallbackProvider(quietLog(), a, b)
	_, _, err := fp.Chat(context.Background(), ChatRequest{})
	if err == nil {
		t.Fatal("全 fail 应返错误")
	}
	if !errors.Is(err, ErrProviderRateLimit) {
		t.Fatalf("应包含最后一个 err，got %v", err)
	}
}

func TestFallback_Chat_CtxCancel_NoFallback(t *testing.T) {
	called2 := false
	a := &fakeProvider{name: "a", chatFn: func(_ context.Context, _ ChatRequest) (string, Usage, error) {
		return "", Usage{}, context.Canceled
	}}
	b := &fakeProvider{name: "b", chatFn: func(_ context.Context, _ ChatRequest) (string, Usage, error) {
		called2 = true
		return "", Usage{}, nil
	}}
	fp := NewFallbackProvider(quietLog(), a, b)
	_, _, err := fp.Chat(context.Background(), ChatRequest{})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("ctx cancel 应直接返，got %v", err)
	}
	if called2 {
		t.Fatal("ctx cancel 时不该尝试 secondary")
	}
}

func TestFallback_Stream_PrimaryFailBeforeChunk_FallbackEmits(t *testing.T) {
	a := &fakeProvider{
		name: "a",
		streamFn: func(_ context.Context, _ ChatRequest, _ func(Chunk) bool) (Usage, error) {
			// 不吐 chunk 直接错
			return Usage{}, ErrProviderUnavailable
		},
	}
	b := &fakeProvider{
		name: "b",
		streamFn: func(_ context.Context, _ ChatRequest, onChunk func(Chunk) bool) (Usage, error) {
			onChunk(Chunk{Content: "hello"})
			onChunk(Chunk{Content: " world"})
			return Usage{Model: "b"}, nil
		},
	}
	fp := NewFallbackProvider(quietLog(), a, b)
	var collected []string
	usage, err := fp.ChatStream(context.Background(), ChatRequest{}, func(c Chunk) bool {
		collected = append(collected, c.Content)
		return true
	})
	if err != nil {
		t.Fatalf("应 fallback 成功，err=%v", err)
	}
	if usage.Model != "b" {
		t.Fatalf("应是 b 的 usage，got %v", usage)
	}
	if strings.Join(collected, "") != "hello world" {
		t.Fatalf("应只收到 b 的 chunks，got %v", collected)
	}
}

func TestFallback_Stream_AfterEmit_NoFallback(t *testing.T) {
	a := &fakeProvider{
		name: "a",
		streamFn: func(_ context.Context, _ ChatRequest, onChunk func(Chunk) bool) (Usage, error) {
			onChunk(Chunk{Content: "halfway "})
			// 然后失败
			return Usage{Model: "a"}, ErrProviderUnavailable
		},
	}
	called2 := false
	b := &fakeProvider{
		name: "b",
		streamFn: func(_ context.Context, _ ChatRequest, _ func(Chunk) bool) (Usage, error) {
			called2 = true
			return Usage{}, nil
		},
	}
	fp := NewFallbackProvider(quietLog(), a, b)
	var collected []string
	_, err := fp.ChatStream(context.Background(), ChatRequest{}, func(c Chunk) bool {
		collected = append(collected, c.Content)
		return true
	})
	if !errors.Is(err, ErrProviderUnavailable) {
		t.Fatalf("已 emit 后失败应返 a 的 err，got %v", err)
	}
	if called2 {
		t.Fatal("已 emit 后不该 fallback 到 b")
	}
	if strings.Join(collected, "") != "halfway " {
		t.Fatalf("应只收到 a 已吐的 chunk，got %v", collected)
	}
}

func TestBuildFallbackChain_OnlyOneProvider_ReturnsItDirectly(t *testing.T) {
	mock := NewMockProvider()
	reg := NewRegistry(mock)
	got := BuildFallbackChain(reg, []string{"mock"}, quietLog())
	if got == nil {
		t.Fatal("不该返 nil")
	}
	if _, isWrap := got.(*FallbackProvider); isWrap {
		t.Fatal("单 provider 不应包装为 FallbackProvider")
	}
}

func TestBuildFallbackChain_TwoProviders_WrapsAsFallback(t *testing.T) {
	mock := NewMockProvider()
	mock2 := &fakeProvider{name: "mock2", chatFn: func(_ context.Context, _ ChatRequest) (string, Usage, error) {
		return "ok", Usage{}, nil
	}}
	reg := NewRegistry(mock, mock2)
	got := BuildFallbackChain(reg, []string{"mock", "mock2"}, quietLog())
	if got == nil {
		t.Fatal("不该返 nil")
	}
	fp, ok := got.(*FallbackProvider)
	if !ok {
		t.Fatal("应包装为 FallbackProvider")
	}
	if len(fp.chain) != 2 {
		t.Fatalf("chain 长 = 2，got %d", len(fp.chain))
	}
}

func TestBuildFallbackChain_UnknownNamesSkipped(t *testing.T) {
	mock := NewMockProvider()
	reg := NewRegistry(mock)
	got := BuildFallbackChain(reg, []string{"unknown1", "mock", "unknown2"}, quietLog())
	if got == nil {
		t.Fatal("不该返 nil（mock 还在）")
	}
	if _, isWrap := got.(*FallbackProvider); isWrap {
		t.Fatal("过滤后只剩 mock，应直接返")
	}
}

func TestBuildFallbackChain_EmptyOrNil(t *testing.T) {
	if got := BuildFallbackChain(nil, []string{"x"}, quietLog()); got != nil {
		t.Fatal("nil registry 应返 nil")
	}
	mock := NewMockProvider()
	reg := NewRegistry(mock)
	if got := BuildFallbackChain(reg, []string{}, quietLog()); got != nil {
		t.Fatal("空 names 应返 nil")
	}
	if got := BuildFallbackChain(reg, []string{"none"}, quietLog()); got != nil {
		t.Fatal("无匹配应返 nil")
	}
}

func TestFallback_Name(t *testing.T) {
	fp := NewFallbackProvider(quietLog(),
		&fakeProvider{name: "deepseek"},
		&fakeProvider{name: "mock"})
	if got := fp.Name(); got != "fallback(deepseek>mock)" {
		t.Fatalf("name 不对，got %s", got)
	}
}
