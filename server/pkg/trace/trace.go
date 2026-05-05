// 分布式追踪抽象 — 不绑定具体后端（OTel SDK / Jaeger / Tempo），
// 让各服务在自己的 main 里选择实现。本包提供 noop 默认值，让无 trace 配置的开发环境也能跑。
//
// 用法：
//   tr := trace.FromContext(ctx)        // 拿当前 span 所属 tracer；无则 noop
//   ctx, span := tr.Start(ctx, "name")
//   defer span.End()
package trace

import (
	"context"
	"sync"
)

// Tracer 创建 span。
type Tracer interface {
	Start(ctx context.Context, name string) (context.Context, Span)
}

// Span 单个跨度。
type Span interface {
	End()
	SetAttribute(key string, value any)
	RecordError(err error)
}

// —— Noop 实现（默认） ——

type noopTracer struct{}

func (noopTracer) Start(ctx context.Context, _ string) (context.Context, Span) {
	return ctx, noopSpan{}
}

type noopSpan struct{}

func (noopSpan) End()                                   {}
func (noopSpan) SetAttribute(_ string, _ any)           {}
func (noopSpan) RecordError(_ error)                    {}

// Noop 是默认 tracer；服务在 main 里替换为真实实现（例 OTel）。
var Noop Tracer = noopTracer{}

// —— Context 注入 ——

type ctxKey struct{}

// FromContext 取 ctx 里挂载的 Tracer；缺则返回 Noop。
func FromContext(ctx context.Context) Tracer {
	if v, ok := ctx.Value(ctxKey{}).(Tracer); ok && v != nil {
		return v
	}
	return Noop
}

// WithTracer 把 Tracer 挂到 ctx 上传递。
func WithTracer(ctx context.Context, tr Tracer) context.Context {
	return context.WithValue(ctx, ctxKey{}, tr)
}

// —— Recording 实现（测试用） ——

// Recording 记录所有 span 名 + 属性 + 错误，便于断言。
type Recording struct {
	mu    sync.Mutex
	Spans []*RecSpan
}

type RecSpan struct {
	Name       string
	Attributes map[string]any
	Errors     []error
	Ended      bool
}

func (r *Recording) Start(ctx context.Context, name string) (context.Context, Span) {
	s := &RecSpan{Name: name, Attributes: map[string]any{}}
	r.mu.Lock()
	r.Spans = append(r.Spans, s)
	r.mu.Unlock()
	return ctx, &recSpanWrap{rs: s, parent: r}
}

type recSpanWrap struct {
	rs     *RecSpan
	parent *Recording
}

func (s *recSpanWrap) End()                              { s.parent.mu.Lock(); s.rs.Ended = true; s.parent.mu.Unlock() }
func (s *recSpanWrap) SetAttribute(k string, v any)      { s.parent.mu.Lock(); s.rs.Attributes[k] = v; s.parent.mu.Unlock() }
func (s *recSpanWrap) RecordError(err error)             { s.parent.mu.Lock(); s.rs.Errors = append(s.rs.Errors, err); s.parent.mu.Unlock() }
